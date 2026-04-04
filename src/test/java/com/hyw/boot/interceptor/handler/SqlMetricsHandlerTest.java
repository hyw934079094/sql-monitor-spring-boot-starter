package com.hyw.boot.interceptor.handler;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.model.SqlInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlMetricsHandler 单元测试
 */
class SqlMetricsHandlerTest {

    private SlowSqlProperties properties;
    private SimpleMeterRegistry meterRegistry;
    private Cache<String, Timer> timerCache;
    private Cache<String, Counter> counterCache;
    private SqlMetricsHandler handler;
    private List<SlowSqlHandler> slowSqlHandlers;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.init();
        // 默认已改为关闭，这里显式开启以验证指标上报行为
        properties.getMetrics().setEnabled(true);

        meterRegistry = new SimpleMeterRegistry();
        timerCache = Caffeine.newBuilder().maximumSize(100).build();
        counterCache = Caffeine.newBuilder().maximumSize(100).build();
        slowSqlHandlers = new ArrayList<>();

        handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, slowSqlHandlers);
    }

    private SqlInfo buildSqlInfo(String sqlId, String sqlType, List<String> tables, String dbType) {
        return SqlInfo.builder()
                .sqlId(sqlId)
                .sqlType(sqlType)
                .tables(tables)
                .filteredSql("SELECT * FROM test")
                .dbType(dbType)
                .build();
    }

    // ====================================================================
    // Micrometer 指标上报
    // ====================================================================

    @Nested
    @DisplayName("Micrometer 指标")
    class MetricsTests {

        @Test
        @DisplayName("慢 SQL 应记录 Timer + Counter")
        void shouldRecordTimerAndCounterForSlowSql() {
            SqlInfo sqlInfo = buildSqlInfo("com.test.Mapper.select", "SELECT",
                    List.of("user"), "mysql");

            handler.handleSqlMetrics(sqlInfo, "com.test.Mapper.select", 2000, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.count()).isEqualTo(1);

            Counter counter = meterRegistry.find("sql.slow.count").counter();
            assertThat(counter).isNotNull();
            assertThat(counter.count()).isEqualTo(1.0);
        }

        @Test
        @DisplayName("非慢 SQL 只记录 Timer，不记录 slow Counter")
        void shouldOnlyRecordTimerForNormalSql() {
            SqlInfo sqlInfo = buildSqlInfo("com.test.Mapper.select", "SELECT",
                    List.of("user"), "mysql");

            handler.handleSqlMetrics(sqlInfo, "com.test.Mapper.select", 100, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();

            Counter counter = meterRegistry.find("sql.slow.count").counter();
            assertThat(counter).isNull();
        }

        @Test
        @DisplayName("sqlId 为 null 时降级为 'unknown' tag")
        void shouldHandleNullSqlId() {
            SqlInfo sqlInfo = buildSqlInfo(null, "SELECT", List.of("user"), "mysql");

            handler.handleSqlMetrics(sqlInfo, null, 2000, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();
            assertThat(timer.getId().getTags().stream()
                    .noneMatch(t -> t.getValue() == null || t.getValue().equals("null"))).isTrue();
        }

        @Test
        @DisplayName("tables 为 null 时降级为 'unknown' tag")
        void shouldHandleNullTables() {
            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", null, "mysql");

            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 100, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("dbType 为 null 时降级为 'unknown'")
        void shouldHandleNullDbType() {
            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), null);

            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 100, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();
        }

        @Test
        @DisplayName("Micrometer 未启用时不上报")
        void shouldSkipMetricsWhenDisabled() {
            properties.getMetrics().setEnabled(false);
            handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, slowSqlHandlers);

            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 100, null, "query");

            assertThat(meterRegistry.find("sql.execution.time").timer()).isNull();
        }

        @Test
        @DisplayName("MeterRegistry 为 null 时不抛异常")
        void shouldHandleNullMeterRegistry() {
            handler = new SqlMetricsHandler(properties, null, timerCache, counterCache, slowSqlHandlers);

            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 2000, null, "query");
        }

        @Test
        @DisplayName("tables tag 超长截断")
        void shouldTruncateLongTablesTag() {
            List<String> longTableList = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                longTableList.add("very_long_table_name_" + i);
            }
            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", longTableList, "mysql");

            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 100, null, "query");

            Timer timer = meterRegistry.find("sql.execution.time").timer();
            assertThat(timer).isNotNull();
            String tablesTag = timer.getId().getTag("tables");
            assertThat(tablesTag).isNotNull();
            assertThat(tablesTag.length()).isLessThanOrEqualTo(51);
        }
    }

    // ====================================================================
    // SlowSqlHandler SPI 回调
    // ====================================================================

    @Nested
    @DisplayName("SlowSqlHandler 回调")
    class HandlerCallbackTests {

        @Test
        @DisplayName("慢 SQL 触发 Handler 回调")
        void shouldCallHandlerForSlowSql() {
            AtomicReference<com.hyw.boot.model.SlowSqlLog> captured = new AtomicReference<>();
            slowSqlHandlers.add(captured::set);
            handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, slowSqlHandlers);

            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 2000, null, "query");

            assertThat(captured.get()).isNotNull();
            assertThat(captured.get().getCost()).isEqualTo(2000);
            assertThat(captured.get().getSqlId()).isEqualTo("test.Mapper.select");
        }

        @Test
        @DisplayName("非慢 SQL 不触发 Handler 回调")
        void shouldNotCallHandlerForNormalSql() {
            AtomicReference<com.hyw.boot.model.SlowSqlLog> captured = new AtomicReference<>();
            slowSqlHandlers.add(captured::set);
            handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, slowSqlHandlers);

            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 100, null, "query");

            assertThat(captured.get()).isNull();
        }

        @Test
        @DisplayName("Handler 异常不影响其他 Handler")
        void shouldIsolateHandlerExceptions() {
            AtomicReference<Boolean> secondCalled = new AtomicReference<>(false);
            slowSqlHandlers.add(log -> { throw new RuntimeException("handler error"); });
            slowSqlHandlers.add(log -> secondCalled.set(true));
            handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, slowSqlHandlers);

            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 2000, null, "query");

            assertThat(secondCalled.get()).isTrue();
        }

        @Test
        @DisplayName("handlers 为 null 时安全降级")
        void shouldHandleNullHandlers() {
            handler = new SqlMetricsHandler(properties, meterRegistry, timerCache, counterCache, null);
            SqlInfo sqlInfo = buildSqlInfo("test.Mapper.select", "SELECT", List.of("user"), "mysql");
            handler.handleSqlMetrics(sqlInfo, "test.Mapper.select", 2000, null, "query");
        }
    }
}