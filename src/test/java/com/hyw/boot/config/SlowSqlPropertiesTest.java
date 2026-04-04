package com.hyw.boot.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlowSqlProperties 配置验证测试
 */
class SlowSqlPropertiesTest {

    private SlowSqlProperties props;

    @BeforeEach
    void setUp() {
        props = new SlowSqlProperties();
    }

    // ====================================================================
    // 默认值合理性
    // ====================================================================

    @Nested
    @DisplayName("默认值")
    class DefaultValueTests {

        @Test
        @DisplayName("默认启用")
        void shouldBeEnabledByDefault() {
            assertThat(props.isEnabled()).isTrue();
        }

        @Test
        @DisplayName("默认慢 SQL 阈值 1000ms")
        void defaultSlowThreshold() {
            assertThat(props.getSlowThreshold()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("默认严重慢 SQL 阈值 5000ms")
        void defaultCriticalThreshold() {
            assertThat(props.getCriticalThreshold()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("默认采样率 1%")
        void defaultSampleRate() {
            assertThat(props.getSampleRate()).isEqualTo(0.01);
        }

        @Test
        @DisplayName("默认敏感字段列表非空")
        void defaultSensitiveFieldsNotEmpty() {
            assertThat(props.getSensitive().getSensitiveFields()).isNotEmpty();
            assertThat(props.getSensitive().getSensitiveFields()).contains("password", "token");
        }

        @Test
        @DisplayName("默认线程池配置合理")
        void defaultPoolConfig() {
            SlowSqlProperties.PoolConfig pool = props.getPool();
            assertThat(pool.getCoreSize()).isEqualTo(2);
            assertThat(pool.getMaxSize()).isEqualTo(4);
            assertThat(pool.getQueueCapacity()).isEqualTo(1000);
        }

        @Test
        @DisplayName("默认 MDC keys 包含 traceId")
        void defaultMdcKeys() {
            assertThat(props.getMdc().getMdcKeys()).contains("traceId", "userId");
        }

        @Test
        @DisplayName("默认 Micrometer 关闭")
        void defaultMetricsConfig() {
            assertThat(props.getMetrics().isEnabled()).isFalse();
            assertThat(props.getMetrics().isIncludeSqlId()).isFalse();
        }
    }

    // ====================================================================
    // 配置验证（边界值修正）
    // ====================================================================

    @Nested
    @DisplayName("配置验证")
    class ValidationTests {

        @Test
        @DisplayName("slowThreshold <= 0 修正为 1000")
        void shouldFixNegativeSlowThreshold() {
            props.setSlowThreshold(-1);
            props.init();
            assertThat(props.getSlowThreshold()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("criticalThreshold < slowThreshold 修正为 2x slowThreshold")
        void shouldFixCriticalLowerThanSlow() {
            props.setSlowThreshold(2000);
            props.setCriticalThreshold(1000);
            props.init();
            assertThat(props.getCriticalThreshold()).isEqualTo(4000L);
        }

        @Test
        @DisplayName("sampleRate 超出 [0,1] 范围自动钳位")
        void shouldClampSampleRate() {
            props.setSampleRate(1.5);
            props.init();
            assertThat(props.getSampleRate()).isEqualTo(1.0);

            props.setSampleRate(-0.5);
            props.init();
            assertThat(props.getSampleRate()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("maxSqlLength < 100 修正为 100")
        void shouldFixTooSmallMaxSqlLength() {
            props.setMaxSqlLength(10);
            props.init();
            assertThat(props.getMaxSqlLength()).isEqualTo(100);
        }

        @Test
        @DisplayName("线程池 maxSize < coreSize 自动修正")
        void shouldFixMaxSizeLowerThanCoreSize() {
            props.getPool().setCoreSize(8);
            props.getPool().setMaxSize(2);
            props.init();
            assertThat(props.getPool().getMaxSize()).isGreaterThanOrEqualTo(props.getPool().getCoreSize());
        }

        @Test
        @DisplayName("线程池 coreSize < 1 修正为 1")
        void shouldFixZeroCoreSize() {
            props.getPool().setCoreSize(0);
            props.init();
            assertThat(props.getPool().getCoreSize()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("sqlParseMaxLength < 1000 修正为 1000")
        void shouldFixTooSmallSqlParseMaxLength() {
            props.setSqlParseMaxLength(100);
            props.init();
            assertThat(props.getSqlParseMaxLength()).isEqualTo(1000);
        }

        @Test
        @DisplayName("sqlParseMaxLength > 100000 修正为 100000")
        void shouldFixTooLargeSqlParseMaxLength() {
            props.setSqlParseMaxLength(200_000);
            props.init();
            assertThat(props.getSqlParseMaxLength()).isEqualTo(100_000);
        }
    }

    // ====================================================================
    // mergeProperties
    // ====================================================================

    @Nested
    @DisplayName("mergeProperties")
    class MergeTests {

        @Test
        @DisplayName("合并属性并触发验证")
        void shouldMergeAndValidate() {
            Properties p = new Properties();
            p.setProperty("slowThreshold", "3000");
            p.setProperty("sampleRate", "0.5");
            props.mergeProperties(p);

            assertThat(props.getSlowThreshold()).isEqualTo(3000L);
            assertThat(props.getSampleRate()).isEqualTo(0.5);
        }

        @Test
        @DisplayName("非法值使用默认")
        void shouldUseDefaultForInvalidValues() {
            Properties p = new Properties();
            p.setProperty("slowThreshold", "not_a_number");
            props.mergeProperties(p);

            assertThat(props.getSlowThreshold()).isEqualTo(1000L);
        }
    }

    // ====================================================================
    // ConfigChangeListener
    // ====================================================================

    @Nested
    @DisplayName("配置变更通知")
    class ListenerTests {

        @Test
        @DisplayName("配置变更时触发监听器")
        void shouldNotifyListenerOnChange() {
            AtomicReference<SlowSqlProperties.SlowSqlConfigSnapshot> oldRef = new AtomicReference<>();
            AtomicReference<SlowSqlProperties.SlowSqlConfigSnapshot> newRef = new AtomicReference<>();

            props.addListener((oldConfig, newConfig) -> {
                oldRef.set(oldConfig);
                newRef.set(newConfig);
            });

            Properties p = new Properties();
            p.setProperty("slowThreshold", "5000");
            props.mergeProperties(p);

            assertThat(oldRef.get()).isNotNull();
            assertThat(newRef.get()).isNotNull();
            assertThat(oldRef.get().getSlowThreshold()).isEqualTo(1000L);
            assertThat(newRef.get().getSlowThreshold()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("配置未变化时不触发监听器")
        void shouldNotNotifyWhenNoChange() {
            AtomicReference<Boolean> called = new AtomicReference<>(false);
            props.addListener((o, n) -> called.set(true));

            // 合并相同的默认值
            Properties p = new Properties();
            p.setProperty("slowThreshold", "1000");
            props.mergeProperties(p);

            assertThat(called.get()).isFalse();
        }

        @Test
        @DisplayName("监听器异常不影响其他监听器")
        void shouldIsolateListenerExceptions() {
            AtomicReference<Boolean> secondCalled = new AtomicReference<>(false);

            props.addListener((o, n) -> { throw new RuntimeException("listener error"); });
            props.addListener((o, n) -> secondCalled.set(true));

            Properties p = new Properties();
            p.setProperty("slowThreshold", "5000");
            props.mergeProperties(p);

            assertThat(secondCalled.get()).isTrue();
        }
    }

    // ====================================================================
    // 额外验证分支（覆盖率补充）
    // ====================================================================

    @Nested
    @DisplayName("额外验证分支")
    class ExtraValidationTests {

        @Test
        @DisplayName("queueCapacity < 100 修正为 100")
        void shouldFixTooSmallQueueCapacity() {
            props.getPool().setQueueCapacity(10);
            props.init();
            assertThat(props.getPool().getQueueCapacity()).isEqualTo(100);
        }

        @Test
        @DisplayName("keepAliveSeconds < 10 修正为 10")
        void shouldFixTooSmallKeepAlive() {
            props.getPool().setKeepAliveSeconds(1);
            props.init();
            assertThat(props.getPool().getKeepAliveSeconds()).isEqualTo(10);
        }

        @Test
        @DisplayName("dbTypeCacheSeconds < 1 修正为 1")
        void shouldFixZeroDbTypeCacheSeconds() {
            props.setDbTypeCacheSeconds(0);
            props.init();
            assertThat(props.getDbTypeCacheSeconds()).isEqualTo(1);
        }

        @Test
        @DisplayName("maxCacheSize < 100 修正为 100")
        void shouldFixTooSmallMaxCacheSize() {
            props.setMaxCacheSize(10);
            props.init();
            assertThat(props.getMaxCacheSize()).isEqualTo(100);
        }
    }

    // ====================================================================
    // mergeProperties 额外分支
    // ====================================================================

    @Nested
    @DisplayName("mergeProperties 额外分支")
    class MergeExtraTests {

        @Test
        @DisplayName("合并 enabled 属性")
        void shouldMergeEnabled() {
            Properties p = new Properties();
            p.setProperty("enabled", "false");
            props.mergeProperties(p);
            assertThat(props.isEnabled()).isFalse();
        }

        @Test
        @DisplayName("合并 criticalThreshold 属性")
        void shouldMergeCriticalThreshold() {
            Properties p = new Properties();
            p.setProperty("criticalThreshold", "8000");
            props.mergeProperties(p);
            assertThat(props.getCriticalThreshold()).isEqualTo(8000L);
        }

        @Test
        @DisplayName("合并 logEnabled 属性")
        void shouldMergeLogEnabled() {
            Properties p = new Properties();
            p.setProperty("logEnabled", "true");
            props.mergeProperties(p);
            assertThat(props.isLogEnabled()).isTrue();
        }

        @Test
        @DisplayName("合并 maxCacheSize 属性")
        void shouldMergeMaxCacheSize() {
            Properties p = new Properties();
            p.setProperty("maxCacheSize", "2000");
            props.mergeProperties(p);
            assertThat(props.getMaxCacheSize()).isEqualTo(2000);
        }

        @Test
        @DisplayName("非法 criticalThreshold 使用默认值")
        void shouldUseDefaultForInvalidCritical() {
            Properties p = new Properties();
            p.setProperty("criticalThreshold", "invalid");
            props.mergeProperties(p);
            assertThat(props.getCriticalThreshold()).isEqualTo(5000L);
        }

        @Test
        @DisplayName("非法 maxCacheSize 使用默认值")
        void shouldUseDefaultForInvalidMaxCacheSize() {
            Properties p = new Properties();
            p.setProperty("maxCacheSize", "invalid");
            props.mergeProperties(p);
            assertThat(props.getMaxCacheSize()).isEqualTo(1000);
        }
    }

    // ====================================================================
    // refreshConfig
    // ====================================================================

    @Nested
    @DisplayName("refreshConfig")
    class RefreshConfigTests {

        @Test
        @DisplayName("无 Environment 时 refreshConfig 安全返回")
        void shouldHandleNullEnvironment() {
            props.refreshConfig(); // 不抛异常
            assertThat(props.getSlowThreshold()).isEqualTo(1000L);
        }

        @Test
        @DisplayName("有 Environment 时从中读取配置")
        void shouldRefreshFromEnvironment() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("hyw.sql.monitor.slow-threshold", "3000");
            env.setProperty("hyw.sql.monitor.sample-rate", "0.5");
            env.setProperty("hyw.sql.monitor.enabled", "false");
            env.setProperty("hyw.sql.monitor.log-enabled", "true");
            env.setProperty("hyw.sql.monitor.max-sql-length", "500");
            env.setProperty("hyw.sql.monitor.max-cache-size", "2000");
            env.setProperty("hyw.sql.monitor.critical-threshold", "10000");
            env.setProperty("hyw.sql.monitor.mdc.monitor-rate", "0.1");
            env.setProperty("hyw.sql.monitor.mdc.alert-threshold", "95.0");
            props.setEnvironment(env);

            props.refreshConfig();

            assertThat(props.getSlowThreshold()).isEqualTo(3000L);
            assertThat(props.getSampleRate()).isEqualTo(0.5);
            assertThat(props.isEnabled()).isFalse();
            assertThat(props.isLogEnabled()).isTrue();
            assertThat(props.getMaxSqlLength()).isEqualTo(500);
            assertThat(props.getMaxCacheSize()).isEqualTo(2000);
            assertThat(props.getCriticalThreshold()).isEqualTo(10000L);
            assertThat(props.getMdc().getMonitorRate()).isEqualTo(0.1);
            assertThat(props.getMdc().getAlertThreshold()).isEqualTo(95.0);
        }

        @Test
        @DisplayName("refreshConfig 触发监听器通知")
        void shouldNotifyListenersOnRefresh() {
            MockEnvironment env = new MockEnvironment();
            env.setProperty("hyw.sql.monitor.slow-threshold", "5000");
            props.setEnvironment(env);

            AtomicReference<Boolean> called = new AtomicReference<>(false);
            props.addListener((o, n) -> called.set(true));

            props.refreshConfig();

            assertThat(called.get()).isTrue();
        }
    }
}
