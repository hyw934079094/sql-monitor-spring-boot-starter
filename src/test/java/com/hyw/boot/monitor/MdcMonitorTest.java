package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MdcMonitor 单元测试
 */
class MdcMonitorTest {

    private SlowSqlProperties properties;
    private MdcMonitor monitor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        // 100% 采样率确保每次都统计
        properties.getMdc().setMonitorRate(1.0);
        monitor = new MdcMonitor(properties);
        MDC.clear();
    }

    @Test
    @DisplayName("所有 MDC keys 存在时记录为 hit")
    void shouldRecordHitWhenAllKeysPresent() {
        // 设置所有默认 MDC keys
        for (String key : properties.getMdc().getMdcKeys()) {
            MDC.put(key, "value-" + key);
        }

        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getStats();
        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getHit()).isEqualTo(1);
        assertThat(stats.getMiss()).isEqualTo(0);
    }

    @Test
    @DisplayName("MDC keys 缺失时记录为 miss")
    void shouldRecordMissWhenKeysMissing() {
        // 不设置任何 MDC key
        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getStats();
        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getHit()).isEqualTo(0);
        assertThat(stats.getMiss()).isEqualTo(1);
    }

    @Test
    @DisplayName("missByKey 按 key 维度统计丢失次数")
    void shouldTrackMissByKey() {
        // 只设置 traceId，不设置其他 key
        MDC.put("traceId", "trace-1");

        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getStats();
        assertThat(stats.getMiss()).isEqualTo(1);
        // traceId 存在，但其他 key 丢失
        assertThat(stats.getMissByKey()).doesNotContainKey("traceId");
        assertThat(stats.getMissByKey()).containsKey("userId");
    }

    @Test
    @DisplayName("getAndResetStats 获取并原子重置")
    void shouldGetAndResetAtomically() {
        MDC.put("traceId", "trace-1");
        monitor.recordMdcStatus();
        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getAndResetStats();
        assertThat(stats.getTotal()).isEqualTo(2);

        // 重置后应为 0
        MdcMonitor.MdcStats afterReset = monitor.getStats();
        assertThat(afterReset.getTotal()).isEqualTo(0);
        assertThat(afterReset.getHit()).isEqualTo(0);
        assertThat(afterReset.getMiss()).isEqualTo(0);
    }

    @Test
    @DisplayName("hitRate 计算正确")
    void shouldCalculateHitRate() {
        // 全部 key 都设置 → hit
        for (String key : properties.getMdc().getMdcKeys()) {
            MDC.put(key, "val");
        }
        monitor.recordMdcStatus();
        monitor.recordMdcStatus();

        // 清空 MDC → miss
        MDC.clear();
        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getStats();
        // 2 hit / (2 hit + 1 miss) = 66.67%
        assertThat(stats.getHitRate()).isGreaterThan(66.0).isLessThan(67.0);
    }

    @Test
    @DisplayName("hitRate 无采样时返回 100%")
    void shouldReturn100WhenNoSamples() {
        MdcMonitor.MdcStats stats = monitor.getStats();
        assertThat(stats.getHitRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("采样率为 0 时不统计 hit/miss（但仍计总数）")
    void shouldSkipStatsWhenMonitorRateZero() {
        properties.getMdc().setMonitorRate(0.0);

        monitor.recordMdcStatus();
        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getStats();
        assertThat(stats.getTotal()).isEqualTo(2);
        assertThat(stats.getHit()).isEqualTo(0);
        assertThat(stats.getMiss()).isEqualTo(0);
    }
}
