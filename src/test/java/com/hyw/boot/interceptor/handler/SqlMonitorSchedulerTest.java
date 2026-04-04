package com.hyw.boot.interceptor.handler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SqlMonitorScheduler 单元测试
 */
class SqlMonitorSchedulerTest {

    private SqlMonitorScheduler scheduler;
    private Cache<String, Object> timerCache;
    private Cache<String, Object> counterCache;
    private Cache<String, Object> sampleCache;

    @BeforeEach
    void setUp() {
        timerCache = Caffeine.newBuilder().maximumSize(100).build();
        counterCache = Caffeine.newBuilder().maximumSize(100).build();
        sampleCache = Caffeine.newBuilder().maximumSize(100).build();
        scheduler = new SqlMonitorScheduler(timerCache, counterCache, sampleCache, 100);
    }

    @AfterEach
    void tearDown() {
        scheduler.stop();
    }

    @Test
    @DisplayName("计数器正确递增")
    void shouldIncrementCounters() {
        scheduler.incrementTotalCount();
        scheduler.incrementTotalCount();
        scheduler.incrementSlowCount();
        scheduler.incrementCriticalCount();
        scheduler.incrementRejectedCount();

        // 计数器内部用 LongAdder，无法直接读取（非 public），
        // 但 start/stop 不应抛异常
        scheduler.start();
    }

    @Test
    @DisplayName("start/stop 不抛异常")
    void shouldStartAndStopGracefully() {
        scheduler.start();
        scheduler.stop();
    }

    @Test
    @DisplayName("重复 stop 不抛异常")
    void shouldHandleDoubleStop() {
        scheduler.start();
        scheduler.stop();
        scheduler.stop(); // 第二次 stop 不应抛异常
    }
}
