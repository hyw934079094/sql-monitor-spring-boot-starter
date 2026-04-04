package com.hyw.boot.monitor;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.*;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MdcMonitor 额外覆盖测试（补齐分支覆盖）
 */
class MdcMonitorExtraTest {

    private SlowSqlProperties properties;
    private MdcMonitor monitor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.init();
        properties.getMdc().setMonitorRate(1.0);
        monitor = new MdcMonitor(properties);
        monitor.init();
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    @DisplayName("全部 key 存在时应计为 hit")
    void shouldCountHitWhenAllKeysPresent() {
        // 填充所有默认监控 key
        properties.getMdc().getMdcKeys().forEach(k -> MDC.put(k, "v"));

        monitor.recordMdcStatus();

        MdcMonitor.MdcStats stats = monitor.getAndResetStats();
        assertThat(stats.getTotal()).isEqualTo(1);
        assertThat(stats.getHit()).isEqualTo(1);
        assertThat(stats.getMiss()).isEqualTo(0);
        assertThat(stats.getHitRate()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("miss 达到 100 时应走 warn 分支（不验证日志，仅验证计数）")
    void shouldAccumulateMisses() {
        MDC.clear();
        for (int i = 0; i < 100; i++) {
            monitor.recordMdcStatus();
        }
        MdcMonitor.MdcStats stats = monitor.getAndResetStats();
        assertThat(stats.getTotal()).isEqualTo(100);
        assertThat(stats.getMiss()).isGreaterThan(0);
        assertThat(stats.getMissByKey()).isNotEmpty();
        assertThat(stats.getMissDetails()).isNotBlank();
    }

    @Test
    @DisplayName("getStats 应返回只读快照且不重置")
    void shouldGetStatsWithoutReset() {
        MDC.clear();
        monitor.recordMdcStatus();

        MdcMonitor.MdcStats snapshot = monitor.getStats();
        assertThat(snapshot.getTotal()).isEqualTo(1);

        // 再次获取并重置，total 仍应为 1
        MdcMonitor.MdcStats stats = monitor.getAndResetStats();
        assertThat(stats.getTotal()).isEqualTo(1);

        // 重置后再次 getStats 应为 0
        assertThat(monitor.getStats().getTotal()).isEqualTo(0);
    }
}
