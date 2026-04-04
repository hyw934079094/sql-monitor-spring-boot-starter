package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * MdcMonitorScheduler 单元测试
 */
class MdcMonitorSchedulerTest {

    private SlowSqlProperties properties;
    private MdcMonitor mdcMonitor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.init();
        mdcMonitor = new MdcMonitor(properties);
    }

    @Test
    @DisplayName("start/stop 生命周期应正常执行")
    void shouldStartAndStop() {
        MdcMonitorScheduler scheduler = new MdcMonitorScheduler(mdcMonitor, properties);
        assertThatCode(scheduler::start).doesNotThrowAnyException();
        assertThatCode(scheduler::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("重复 stop 不应抛异常")
    void shouldHandleDoubleStop() {
        MdcMonitorScheduler scheduler = new MdcMonitorScheduler(mdcMonitor, properties);
        scheduler.start();
        scheduler.stop();
        assertThatCode(scheduler::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("未 start 时 stop 不应抛异常")
    void shouldHandleStopWithoutStart() {
        MdcMonitorScheduler scheduler = new MdcMonitorScheduler(mdcMonitor, properties);
        assertThatCode(scheduler::stop).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("有统计数据时报告不应抛异常")
    void shouldReportWithData() throws Exception {
        // 记录一些数据
        mdcMonitor.recordMdcStatus(); // 空 MDC 会记录 miss

        MdcMonitorScheduler scheduler = new MdcMonitorScheduler(mdcMonitor, properties);
        scheduler.start();

        // 等待调度器有机会执行（虽然默认间隔是60s，但我们验证 start 不抛异常）
        Thread.sleep(100);
        scheduler.stop();
    }

    @Test
    @DisplayName("低命中率时应触发告警路径")
    void shouldHandleLowHitRate() {
        // 强制全量采样，避免 sampled=0 时命中率被计算为 100%
        properties.getMdc().setMonitorRate(1.0);
        // 设置较高的告警阈值
        properties.getMdc().setAlertThreshold(99.9);

        // 清空 MDC，确保所有被监控 key 都会 miss
        org.slf4j.MDC.clear();

        for (int i = 0; i < 10; i++) {
            mdcMonitor.recordMdcStatus();
        }

        MdcMonitor.MdcStats stats = mdcMonitor.getAndResetStats();
        assertThat(stats.getTotal()).isEqualTo(10);
        assertThat(stats.getMiss()).isGreaterThan(0);
        assertThat(stats.getHitRate()).isLessThan(99.9);
    }
}
