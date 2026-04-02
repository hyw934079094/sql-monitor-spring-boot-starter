package com.hyw.boot.interceptor.handler;

import com.github.benmanes.caffeine.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.*;
import java.util.concurrent.atomic.LongAdder;

/**
 * SQL监控调度器
 * 负责处理统计报告和缓存监控
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlMonitorScheduler {

    private static final Logger log = LoggerFactory.getLogger(SqlMonitorScheduler.class);

    private final LongAdder totalCount = new LongAdder();
    private final LongAdder slowCount = new LongAdder();
    private final LongAdder criticalCount = new LongAdder();
    private final LongAdder rejectedCount = new LongAdder();

    private final Cache<String, ?> timerCache;
    private final Cache<String, ?> counterCache;
    private final Cache<String, ?> sampleCache;
    private final int maxCacheSize;

    private final ScheduledExecutorService scheduler;

    public SqlMonitorScheduler(Cache<String, ?> timerCache, Cache<String, ?> counterCache,
                               Cache<String, ?> sampleCache, int maxCacheSize) {
        this.timerCache = timerCache;
        this.counterCache = counterCache;
        this.sampleCache = sampleCache;
        this.maxCacheSize = maxCacheSize;

        // 单线程调度器即可处理统计报告和缓存监控
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sql-monitor-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 启动调度器
     */
    public void start() {
        startStatReport();
        startCacheMonitor();
    }

    /**
     * 关闭调度器
     */
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
            log.debug("sql-monitor-scheduler 已关闭");
        }
    }

    /**
     * 增加总执行计数
     */
    public void incrementTotalCount() {
        totalCount.increment();
    }

    /**
     * 增加慢SQL计数
     */
    public void incrementSlowCount() {
        slowCount.increment();
    }

    /**
     * 增加严重慢SQL计数
     */
    public void incrementCriticalCount() {
        criticalCount.increment();
    }

    /**
     * 增加被丢弃的任务计数（线程池满时触发）
     */
    public void incrementRejectedCount() {
        rejectedCount.increment();
    }

    /**
     * 启动统计报告
     */
    private void startStatReport() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                long total = totalCount.sumThenReset();
                long slow = slowCount.sumThenReset();
                long critical = criticalCount.sumThenReset();
                long rejected = rejectedCount.sumThenReset();

                if (total > 0) {
                    log.info("SQL监控统计 - 总执行: {}, 慢SQL: {} ({}%), 严重慢SQL: {} ({}%), 丢弃: {}",
                            total,
                            slow, String.format("%.2f", slow * 100.0 / total),
                            critical, String.format("%.2f", critical * 100.0 / total),
                            rejected);
                }
                if (rejected > 0) {
                    log.warn("SQL监控任务被丢弃 {} 次（线程池队列已满），建议增大 pool.queue-capacity 或 pool.max-size", rejected);
                }
            } catch (Exception e) {
                log.error("统计报告执行失败", e);
            }
        }, 1, 1, TimeUnit.MINUTES);
    }

    /**
     * 启动缓存监控
     */
    private void startCacheMonitor() {
        scheduler.scheduleAtFixedRate(() -> {
            try {
                if (log.isDebugEnabled()) {
                    log.debug("Timer缓存统计: size={}",
                            timerCache.estimatedSize());
                    log.debug("Counter缓存统计: size={}",
                            counterCache.estimatedSize());
                    log.debug("采样缓存统计: size={}",
                            sampleCache.estimatedSize());
                }

                if (timerCache.estimatedSize() > maxCacheSize * 0.8) {
                    log.warn("Timer缓存即将达到上限: {}/{}",
                            timerCache.estimatedSize(), maxCacheSize);
                }
                if (counterCache.estimatedSize() > maxCacheSize * 0.8) {
                    log.warn("Counter缓存即将达到上限: {}/{}",
                            counterCache.estimatedSize(), maxCacheSize);
                }
            } catch (Exception e) {
                log.error("缓存监控执行失败", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }
}
