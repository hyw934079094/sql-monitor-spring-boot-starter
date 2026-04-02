package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * MDC监控调度器
 *
 * @author hyw
 * @version 3.0.0
 */
@Component
public class MdcMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(MdcMonitorScheduler.class);

    @Autowired
    private MdcMonitor mdcMonitor;

    @Autowired
    private SlowSqlProperties properties;

    private ScheduledExecutorService scheduler;

    @PostConstruct
    public void init() {
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "mdc-monitor-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    public void destroy() {
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
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void reportMdcStats() {
        MdcMonitor.MdcStats stats = mdcMonitor.getStats();

        if (stats.getTotal() > 0) {
            log.info("MDC传递统计 - 总任务: {}, 成功: {} ({:.2f}%), 失败: {} ({:.2f}%)",
                    stats.getTotal(),
                    stats.getHit(), stats.getHitRate(),
                    stats.getMiss(), (100 - stats.getHitRate()));

            if (stats.getMiss() > 0) {
                log.warn("MDC丢失详情 - {}", stats.getMissDetails());
            }

            double alertThreshold = properties.getMdc().getAlertThreshold();
            if (stats.getHitRate() < alertThreshold) {
                log.error("MDC传递成功率低于阈值 {}%！当前: {:.2f}%, 丢失详情: {}",
                        alertThreshold,
                        stats.getHitRate(),
                        stats.getMissDetails());
            }

            mdcMonitor.resetStats();
        }
    }
}