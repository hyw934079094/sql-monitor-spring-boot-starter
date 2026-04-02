package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MDC监控调度器
 *
 * <p>自管理调度线程，不依赖 Spring {@code @Scheduled} / {@code @EnableScheduling}，
 * 避免 Starter 强制为消费方启用全局调度。</p>
 *
 * @author hyw
 * @version 3.0.0
 */
public class MdcMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MdcMonitorScheduler.class);

    private final MdcMonitor mdcMonitor;
    private final SlowSqlProperties properties;
    private ScheduledExecutorService scheduler;

    public MdcMonitorScheduler(MdcMonitor mdcMonitor, SlowSqlProperties properties) {
        this.mdcMonitor = mdcMonitor;
        this.properties = properties;
    }

    @PostConstruct
    public void start() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdc-monitor-scheduler");
            t.setDaemon(true);
            return t;
        });
        this.scheduler.scheduleWithFixedDelay(this::reportMdcStats, 60, 60, TimeUnit.SECONDS);
        log.debug("MDC监控调度器已启动");
    }

    @PreDestroy
    public void stop() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            log.debug("MDC监控调度器已关闭");
        }
    }

    private void reportMdcStats() {
        try {
            // 原子获取并重置，避免 get/reset 之间的数据丢失
            MdcMonitor.MdcStats stats = mdcMonitor.getAndResetStats();

            if (stats.getTotal() > 0) {
                log.info("MDC传递统计 - 总任务: {}, 成功: {} ({} %), 失败: {} ({} %)",
                        stats.getTotal(),
                        stats.getHit(), String.format("%.2f", stats.getHitRate()),
                        stats.getMiss(), String.format("%.2f", 100 - stats.getHitRate()));

                if (stats.getMiss() > 0) {
                    log.warn("MDC丢失详情 - {}", stats.getMissDetails());
                }

                double alertThreshold = properties.getMdc().getAlertThreshold();
                if (stats.getHitRate() < alertThreshold) {
                    log.error("MDC传递成功率低于阈值 {}%！当前: {} %, 丢失详情: {}",
                            alertThreshold,
                            String.format("%.2f", stats.getHitRate()),
                            stats.getMissDetails());
                }
            }
        } catch (Exception e) {
            log.error("MDC统计报告执行失败", e);
        }
    }
}
