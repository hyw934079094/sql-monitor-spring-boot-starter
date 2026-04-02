package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
@Component
public class MdcMonitor {

    private static final Logger log = LoggerFactory.getLogger(MdcMonitor.class);

    private final SlowSqlProperties properties;
    private final Set<String> monitoredKeys;

    private final AtomicLong totalTaskCount = new AtomicLong(0);
    private final AtomicLong hitCount = new AtomicLong(0);
    private final AtomicLong missCount = new AtomicLong(0);

    // ✅ 使用 ConcurrentHashMap 作为实现，ConcurrentMap 作为接口
    private final ConcurrentMap<String, AtomicLong> missByKey = new ConcurrentHashMap<>();

    public MdcMonitor(SlowSqlProperties properties) {
        this.properties = properties;
        this.monitoredKeys = new HashSet<>(properties.getMdc().getMdcKeys());
    }

    @PostConstruct
    public void init() {
        log.info("MDC监控初始化 - 监控的keys: {}, 采样率: {}, 告警阈值: {}%",
                monitoredKeys,
                properties.getMdc().getMonitorRate(),
                properties.getMdc().getAlertThreshold());
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

        for (String key : monitoredKeys) {
            String value = currentMdc != null ? currentMdc.get(key) : null;
            if (value == null || value.isEmpty()) {
                allKeysPresent = false;
                // ✅ 使用 computeIfAbsent 确保线程安全
                missByKey.computeIfAbsent(key, k -> new AtomicLong(0))
                        .incrementAndGet();
                log.debug("MDC key丢失 - key: {}, 当前MDC: {}", key, currentMdc);
            }
        }

        if (allKeysPresent) {
            hitCount.incrementAndGet();
            log.debug("MDC所有keys传递成功 - keys: {}", monitoredKeys);
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
     * 获取详细统计信息
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
     * 重置统计
     */
    public void resetStats() {
        totalTaskCount.set(0);
        hitCount.set(0);
        missCount.set(0);
        missByKey.clear();
    }

    @Data
    @AllArgsConstructor
    public static class MdcStats {
        private long total;
        private long hit;
        private long miss;
        private Map<String, Long> missByKey;

        public double getHitRate() {
            return total == 0 ? 100.0 : (hit * 100.0 / total);
        }

        public String getMissDetails() {
            return missByKey.entrySet().stream()
                    .map(e -> e.getKey() + ":" + e.getValue())
                    .collect(Collectors.joining(", "));
        }
    }
}