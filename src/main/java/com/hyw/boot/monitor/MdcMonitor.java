package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * MDC监控器
 *
 * @author hyw
 * @version 3.0.0
 */
public class MdcMonitor {

    private static final Logger log = LoggerFactory.getLogger(MdcMonitor.class);

    private final SlowSqlProperties properties;

    private final AtomicLong totalTaskCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    private final ConcurrentMap<String, AtomicLong> missByKey = new ConcurrentHashMap<>();

    public MdcMonitor(SlowSqlProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        log.debug("MDC监控初始化 - 监控的keys: {}, 采样率: {}, 告警阈值: {}%",
                getMonitoredKeys(),
                properties.getMdc().getMonitorRate(),
                properties.getMdc().getAlertThreshold());
    }

    /**
     * 动态获取监控的MDC keys（支持运行时配置刷新）
     */
    private List<String> getMonitoredKeys() {
        return properties.getMdc().getMdcKeys();
    }

    /**
     * 记录MDC命中情况
     */
    public void recordMdcStatus() {
        totalTaskCount.incrementAndGet();

        // 采样统计
        if (ThreadLocalRandom.current().nextDouble() >= properties.getMdc().getMonitorRate()) {
            return;
        }

        boolean allKeysPresent = true;
        Map<String, String> currentMdc = MDC.getCopyOfContextMap();

        for (String key : getMonitoredKeys()) {
            String value = currentMdc != null ? currentMdc.get(key) : null;
            if (value == null || value.isEmpty()) {
                allKeysPresent = false;
                missByKey.computeIfAbsent(key, k -> new AtomicLong(0))
                        .incrementAndGet();
                log.debug("MDC key丢失 - key: {}, 当前MDC: {}", key, currentMdc);
            }
        }

        if (allKeysPresent) {
            hitCount.incrementAndGet();
            log.debug("MDC所有keys传递成功 - keys: {}", getMonitoredKeys());
        } else {
            long miss = missCount.incrementAndGet();

            if (miss % 100 == 0) {
                log.warn("MDC传递丢失累计达到 {} 次，按key统计: {}",
                        miss, getMissStats());
            }
        }
    }

    /**
     * 获取丢失统计信息
     */
    private String getMissStats() {
        return missByKey.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue().get())
                .collect(Collectors.joining(", "));
    }

    /**
     * 获取详细统计信息（只读快照，不重置）
     */
    public MdcStats getStats() {
        Map<String, Long> missDetails = new HashMap<>();
        missByKey.forEach((key, count) -> missDetails.put(key, count.get()));

        return new MdcStats(
                totalTaskCount.get(),
                hitCount.get(),
                missCount.get(),
                missDetails
        );
    }

    /**
     * 原子性地获取统计并重置（用于周期性报告）。
     *
     * <p>使用 {@code getAndSet(0)} 保证每个计数器的获取和重置是原子的，
     * 避免 getStats() + resetStats() 两步操作之间的数据丢失。</p>
     */
    public MdcStats getAndResetStats() {
        long total = totalTaskCount.getAndSet(0);
        long hit = hitCount.getAndSet(0);
        long miss = missCount.getAndSet(0);

        Map<String, Long> missDetails = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : missByKey.entrySet()) {
            long val = entry.getValue().getAndSet(0);
            if (val > 0) {
                missDetails.put(entry.getKey(), val);
            }
        }

        return new MdcStats(total, hit, miss, missDetails);
    }

    @Data
    @AllArgsConstructor
    public static class MdcStats {
        private long total;
        private long hit;
        private long miss;
        private Map<String, Long> missByKey;

        /**
         * 计算采样命中率（基于采样总数，非全量任务数）
         */
        public double getHitRate() {
            long sampled = hit + miss;
            return sampled == 0 ? 100.0 : (hit * 100.0 / sampled);
        }

        public String getMissDetails() {
            return missByKey.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(", "));
        }
    }
}
