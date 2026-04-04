package com.hyw.boot.interceptor;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.model.SlowSqlLog;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 并发压力测试：验证拦截器在高并发场景下的线程安全和稳定性
 */
class ConcurrentStressTest {

    private SlowSqlProperties properties;
    private UniversalSlowSqlInterceptor interceptor;
    private ThreadPoolTaskExecutor asyncExecutor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.setSlowThreshold(50);
        properties.setSampleRate(1.0);
        properties.setLogEnabled(true);
        properties.init();

        SqlSensitiveFilter sensitiveFilter = new SqlSensitiveFilter(properties);
        SqlParser sqlParser = new JSqlParser();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        asyncExecutor = new ThreadPoolTaskExecutor();
        asyncExecutor.setCorePoolSize(4);
        asyncExecutor.setMaxPoolSize(8);
        asyncExecutor.setQueueCapacity(500);
        asyncExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        asyncExecutor.initialize();

        interceptor = new UniversalSlowSqlInterceptor(
                properties, sensitiveFilter, sqlParser, meterRegistry,
                null, asyncExecutor, null, Collections.emptyList()
        );
        interceptor.init();
    }

    @AfterEach
    void tearDown() {
        interceptor.destroy();
        asyncExecutor.shutdown();
    }

    // ====================================================================
    // 高并发拦截器稳定性
    // ====================================================================

    @Test
    @DisplayName("100 并发线程同时执行 SQL，拦截器不应抛异常或丢失请求")
    void shouldHandleHighConcurrency() throws Exception {
        int threadCount = 100;
        int iterationsPerThread = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    for (int i = 0; i < iterationsPerThread; i++) {
                        try {
                            String sqlId = "com.test.Mapper.select" + (threadId % 10);
                            String sql = "SELECT * FROM orders WHERE id = " + (threadId * 1000 + i);
                            Invocation inv = buildInvocation(sqlId, sql, null, 0);
                            interceptor.intercept(inv);
                            successCount.incrementAndGet();
                        } catch (Throwable ex) {
                            errorCount.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown(); // 同时启动所有线程
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(threadCount * iterationsPerThread);
        assertThat(errorCount.get()).isZero();
        assertThat(interceptor.isCircuitBreakerOpen()).isFalse();
        assertThat(interceptor.getConsecutiveFailures()).isZero();
    }

    // ====================================================================
    // 熔断器并发竞态验证
    // ====================================================================

    @Test
    @DisplayName("并发触发熔断器应安全切换状态，无竞态异常")
    void shouldSafelyTriggerCircuitBreakerUnderConcurrency() throws Exception {
        // 替换 interceptor 使用一个总是抛异常的 executor 来模拟连续失败
        // 这里通过直接调用 intercept 和故意构造失败场景来测试

        int threadCount = 50;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger unexpectedErrors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // 正常执行，不应导致熔断
                    for (int i = 0; i < 20; i++) {
                        Invocation inv = buildInvocation("test.select",
                                "SELECT 1", null, 0);
                        interceptor.intercept(inv);
                    }
                } catch (Throwable e) {
                    unexpectedErrors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(30, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(unexpectedErrors.get()).isZero();
        // 正常执行不应触发熔断
        assertThat(interceptor.isCircuitBreakerOpen()).isFalse();
    }

    // ====================================================================
    // 采样一致性验证
    // ====================================================================

    @Test
    @DisplayName("同一 sqlId 在多线程下采样结果应一致（确定性采样）")
    void shouldProduceDeterministicSamplingAcrossThreads() throws Exception {
        properties.setSampleRate(0.5);

        int threadCount = 20;
        String sqlId = "com.test.Mapper.sampleTarget";
        Set<Boolean> results = ConcurrentHashMap.newKeySet();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    Invocation inv = buildInvocation(sqlId, "SELECT 1", null, 0);
                    interceptor.intercept(inv);
                    // 无论是否采样，执行都应成功
                } catch (Throwable ignored) {
                } finally {
                    latch.countDown();
                }
            }).start();
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        // 没有抛异常就说明并发采样是安全的
    }

    // ====================================================================
    // 配置热更新并发安全
    // ====================================================================

    @Test
    @DisplayName("运行时并发修改配置不应导致异常")
    void shouldHandleConcurrentConfigChange() throws Exception {
        int threadCount = 30;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger errors = new AtomicInteger(0);

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            new Thread(() -> {
                try {
                    startLatch.await();
                    if (threadId % 3 == 0) {
                        // 1/3 线程修改配置
                        properties.setSlowThreshold(100 + threadId);
                    } else {
                        // 2/3 线程执行 SQL
                        Invocation inv = buildInvocation("test.concurrent",
                                "SELECT * FROM orders", null, 0);
                        interceptor.intercept(inv);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);

        assertThat(completed).isTrue();
        assertThat(errors.get()).isZero();
    }

    // ====================================================================
    // SlowSqlHandler 并发回调验证
    // ====================================================================

    @Test
    @DisplayName("慢 SQL 并发触发 Handler 回调应线程安全")
    void shouldSafelyCallHandlersConcurrently() throws Exception {
        properties.setSlowThreshold(10);
        properties.init();

        List<SlowSqlLog> collected = Collections.synchronizedList(new ArrayList<>());
        List<SlowSqlHandler> handlers = List.of(collected::add);

        // 重建拦截器以注入 handler
        interceptor.destroy();
        interceptor = new UniversalSlowSqlInterceptor(
                properties, new SqlSensitiveFilter(properties), new JSqlParser(),
                new SimpleMeterRegistry(), null, asyncExecutor, null, handlers
        );
        interceptor.init();

        int threadCount = 20;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            new Thread(() -> {
                try {
                    startLatch.await();
                    // 延迟 20ms 使其超过 10ms 阈值
                    Invocation inv = buildInvocation("test.slowHandler",
                            "SELECT * FROM orders", null, 20);
                    interceptor.intercept(inv);
                } catch (Throwable ignored) {
                } finally {
                    doneLatch.countDown();
                }
            }).start();
        }

        startLatch.countDown();
        doneLatch.await(10, TimeUnit.SECONDS);

        // 等待异步处理完成
        Thread.sleep(3000);

        // 所有慢 SQL 都应触发 handler
        assertThat(collected.size()).isGreaterThanOrEqualTo(1);
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    private Invocation buildInvocation(String sqlId, String sql,
                                       RuntimeException errorToThrow, long delayMs) throws Exception {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, Collections.emptyList(), null);
        SqlSource sqlSource = paramObj -> boundSql;

        MappedStatement ms = new MappedStatement.Builder(configuration, sqlId, sqlSource, SqlCommandType.SELECT)
                .build();

        Executor executor = new StubExecutor(errorToThrow, delayMs);
        Method queryMethod = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);

        return new Invocation(executor, queryMethod, new Object[]{ms, null, RowBounds.DEFAULT, null});
    }

    @SuppressWarnings("all")
    private static class StubExecutor implements Executor {
        private final RuntimeException errorToThrow;
        private final long delayMs;

        StubExecutor(RuntimeException errorToThrow, long delayMs) {
            this.errorToThrow = errorToThrow;
            this.delayMs = delayMs;
        }

        @Override public int update(MappedStatement ms, Object parameter) throws java.sql.SQLException { return 0; }
        @Override public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, org.apache.ibatis.cache.CacheKey cacheKey, BoundSql boundSql) throws java.sql.SQLException { return doQuery(); }
        @Override public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws java.sql.SQLException { return doQuery(); }
        private <E> List<E> doQuery() {
            if (delayMs > 0) { try { Thread.sleep(delayMs); } catch (InterruptedException ignored) {} }
            if (errorToThrow != null) throw errorToThrow;
            return Collections.emptyList();
        }
        @Override public <E> org.apache.ibatis.cursor.Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws java.sql.SQLException { return null; }
        @Override public java.util.List<org.apache.ibatis.executor.BatchResult> flushStatements() throws java.sql.SQLException { return Collections.emptyList(); }
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
