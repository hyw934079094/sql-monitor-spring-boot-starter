package com.hyw.boot.interceptor;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.model.SlowSqlLog;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.mapping.SqlCommandType;
import org.apache.ibatis.mapping.SqlSource;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UniversalSlowSqlInterceptor 单元测试
 */
class UniversalSlowSqlInterceptorTest {

    private SlowSqlProperties properties;
    private SqlSensitiveFilter sensitiveFilter;
    private SqlParser sqlParser;
    private SimpleMeterRegistry meterRegistry;
    private ThreadPoolTaskExecutor asyncExecutor;
    private UniversalSlowSqlInterceptor interceptor;
    private List<SlowSqlHandler> handlers;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.setSlowThreshold(1000);
        properties.setCriticalThreshold(5000);
        properties.setSampleRate(1.0);
        properties.setLogEnabled(true);
        properties.init();

        sensitiveFilter = new SqlSensitiveFilter(properties);
        sqlParser = new JSqlParser();
        meterRegistry = new SimpleMeterRegistry();
        handlers = new ArrayList<>();

        asyncExecutor = new ThreadPoolTaskExecutor();
        asyncExecutor.setCorePoolSize(2);
        asyncExecutor.setMaxPoolSize(4);
        asyncExecutor.setQueueCapacity(100);
        asyncExecutor.setThreadNamePrefix("test-sql-monitor-");
        asyncExecutor.initialize();

        interceptor = new UniversalSlowSqlInterceptor(
                properties, sensitiveFilter, sqlParser, meterRegistry,
                null, asyncExecutor, null, handlers
        );
        interceptor.init();
    }

    @AfterEach
    void tearDown() {
        interceptor.destroy();
        asyncExecutor.shutdown();
    }

    // ====================================================================
    // 基本拦截功能
    // ====================================================================

    @Test
    @DisplayName("enabled=false 时直接放行，不触发监控逻辑")
    void shouldBypassWhenDisabled() throws Throwable {
        properties.setEnabled(false);

        Invocation invocation = buildInvocation("com.test.Mapper.select", "SELECT * FROM orders", null);

        Object result = interceptor.intercept(invocation);

        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("正常 SQL 执行应透传结果")
    void shouldPassThroughResult() throws Throwable {
        Invocation invocation = buildInvocation("com.test.Mapper.select", "SELECT * FROM orders", null);

        Object result = interceptor.intercept(invocation);

        // 真实 Executor.query 返回结果
        assertThat(result).isNotNull();
    }

    @Test
    @DisplayName("SQL 执行异常应重新抛出")
    void shouldRethrowException() throws Throwable {
        RuntimeException expected = new RuntimeException("DB error");
        Invocation invocation = buildInvocation("com.test.Mapper.select", "SELECT * FROM orders", expected);

        try {
            interceptor.intercept(invocation);
            Assertions.fail("Should have thrown exception");
        } catch (Exception e) {
            // 真实 Invocation.proceed() 通过 method.invoke() 调用，
            // RuntimeException 会被包装为 InvocationTargetException
            Throwable cause = (e instanceof java.lang.reflect.InvocationTargetException)
                    ? e.getCause() : e;
            assertThat(cause).isSameAs(expected);
        }
    }

    // ====================================================================
    // 慢 SQL 检测
    // ====================================================================

    @Test
    @DisplayName("慢 SQL 应触发 SlowSqlHandler 回调")
    void shouldTriggerHandlerForSlowSql() throws Throwable {
        // 设低阈值以便测试
        properties.setSlowThreshold(50);
        properties.init();

        AtomicReference<SlowSqlLog> captured = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        handlers.add(log -> {
            captured.set(log);
            latch.countDown();
        });
        // 重新构造以注入 handler
        interceptor.destroy();
        interceptor = new UniversalSlowSqlInterceptor(
                properties, sensitiveFilter, sqlParser, meterRegistry,
                null, asyncExecutor, null, handlers
        );
        interceptor.init();

        // 使用一个延迟执行的 Executor 来模拟慢 SQL
        Invocation invocation = buildInvocationWithDelay("com.test.Mapper.slowSelect",
                "SELECT * FROM orders WHERE id = 1", 100);

        interceptor.intercept(invocation);

        // 等待异步处理完成
        assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().getCost()).isGreaterThanOrEqualTo(50);
        assertThat(captured.get().getSqlId()).isEqualTo("com.test.Mapper.slowSelect");
    }

    // ====================================================================
    // 采样逻辑
    // ====================================================================

    @Test
    @DisplayName("sampleRate=0 + logEnabled=false 时不记录采样日志")
    void shouldNotSampleWhenRateIsZero() throws Throwable {
        properties.setSampleRate(0.0);
        properties.setLogEnabled(false);

        Invocation invocation = buildInvocation("com.test.Mapper.select", "SELECT 1", null);

        // 执行不应抛异常
        Object result = interceptor.intercept(invocation);
        assertThat(result).isNotNull();
    }

    // ====================================================================
    // 熔断降级
    // ====================================================================

    @Test
    @DisplayName("plugin() 应正确包装 Executor")
    void shouldWrapExecutor() {
        // 使用匿名实现代替 mock，避免 Java 24 兼容性问题
        Executor simpleExecutor = new StubExecutor();
        Object wrapped = interceptor.plugin(simpleExecutor);
        assertThat(wrapped).isNotNull();
    }

    // ====================================================================
    // 配置变更监听
    // ====================================================================

    @Test
    @DisplayName("sampleRate 变更时应清空采样缓存")
    void shouldInvalidateSampleCacheOnRateChange() {
        SlowSqlProperties.SlowSqlConfigSnapshot oldConfig = new SlowSqlProperties.SlowSqlConfigSnapshot(
                true, 1000, 5000, true, 0.01, 2000, 1000, 10000);
        SlowSqlProperties.SlowSqlConfigSnapshot newConfig = new SlowSqlProperties.SlowSqlConfigSnapshot(
                true, 1000, 5000, true, 0.5, 2000, 1000, 10000);

        // 不应抛异常
        interceptor.onConfigChange(oldConfig, newConfig);
    }

    @Test
    @DisplayName("配置未变化时 onConfigChange 安全执行")
    void shouldHandleSameConfig() {
        SlowSqlProperties.SlowSqlConfigSnapshot config = new SlowSqlProperties.SlowSqlConfigSnapshot(
                true, 1000, 5000, true, 0.01, 2000, 1000, 10000);

        // 不应抛异常
        interceptor.onConfigChange(config, config);
    }

    // ====================================================================
    // 生命周期
    // ====================================================================

    @Test
    @DisplayName("destroy 应安全释放资源")
    void shouldDestroyGracefully() {
        // 不应抛异常
        interceptor.destroy();
        // 重复 destroy 不应抛异常
        interceptor.destroy();
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    /**
     * 构建真实 Invocation（不用 Mockito，兼容 Java 24）
     *
     * @param errorToThrow 如果非 null，Executor.query 会抛出此异常
     */
    private Invocation buildInvocation(String sqlId, String sql, RuntimeException errorToThrow) throws Exception {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, Collections.emptyList(), null);
        SqlSource sqlSource = paramObj -> boundSql;

        MappedStatement ms = new MappedStatement.Builder(configuration, sqlId, sqlSource, SqlCommandType.SELECT)
                .build();

        Executor executor = new StubExecutor(errorToThrow, 0);
        Method queryMethod = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class,
                ResultHandler.class);

        return new Invocation(executor, queryMethod, new Object[]{ms, null, RowBounds.DEFAULT, null});
    }

    /**
     * 构建带延迟的 Invocation（用于模拟慢 SQL）
     */
    private Invocation buildInvocationWithDelay(String sqlId, String sql, long delayMs) throws Exception {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, Collections.emptyList(), null);
        SqlSource sqlSource = paramObj -> boundSql;

        MappedStatement ms = new MappedStatement.Builder(configuration, sqlId, sqlSource, SqlCommandType.SELECT)
                .build();

        Executor executor = new StubExecutor(null, delayMs);
        Method queryMethod = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class,
                ResultHandler.class);

        return new Invocation(executor, queryMethod, new Object[]{ms, null, RowBounds.DEFAULT, null});
    }

    /**
     * 最小 Executor 实现，避免 mock final 类问题（Java 24 兼容）
     */
    @SuppressWarnings("all")
    private static class StubExecutor implements Executor {
        private final RuntimeException errorToThrow;
        private final long delayMs;

        StubExecutor() { this(null, 0); }

        StubExecutor(RuntimeException errorToThrow, long delayMs) {
            this.errorToThrow = errorToThrow;
            this.delayMs = delayMs;
        }

        @Override
        public int update(MappedStatement ms, Object parameter) throws java.sql.SQLException { return 0; }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
                                 ResultHandler resultHandler,
                                 org.apache.ibatis.cache.CacheKey cacheKey,
                                 BoundSql boundSql) throws java.sql.SQLException {
            return doQuery();
        }

        @Override
        public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds,
                                 ResultHandler resultHandler) throws java.sql.SQLException {
            return doQuery();
        }

        private <E> List<E> doQuery() {
            if (delayMs > 0) {
                try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {}
            }
            if (errorToThrow != null) throw errorToThrow;
            return Collections.emptyList();
        }

        @Override public <E> org.apache.ibatis.cursor.Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws java.sql.SQLException { return null; }
        @Override public List<org.apache.ibatis.executor.BatchResult> flushStatements() throws java.sql.SQLException { return Collections.emptyList(); }
        @Override public void commit(boolean required) throws java.sql.SQLException {}
        @Override public void rollback(boolean required) throws java.sql.SQLException {}
        @Override public org.apache.ibatis.cache.CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) { return new org.apache.ibatis.cache.CacheKey(); }
        @Override public boolean isCached(MappedStatement ms, org.apache.ibatis.cache.CacheKey key) { return false; }
        @Override public void clearLocalCache() {}
        @Override public void deferLoad(MappedStatement ms, org.apache.ibatis.reflection.MetaObject resultObject, String property, org.apache.ibatis.cache.CacheKey key, Class<?> targetType) {}
        @Override public org.apache.ibatis.transaction.Transaction getTransaction() { return null; }
        @Override public void close(boolean forceRollback) {}
        @Override public boolean isClosed() { return false; }
        @Override public void setExecutorWrapper(Executor executor) {}
    }
}
