package com.hyw.boot.interceptor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.Hashing;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.interceptor.handler.DatabaseTypeDetector;
import com.hyw.boot.interceptor.handler.SqlInfoExtractor;
import com.hyw.boot.interceptor.handler.SqlMetricsHandler;
import com.hyw.boot.interceptor.handler.SqlMonitorScheduler;
import com.hyw.boot.model.SqlInfo;
import com.hyw.boot.monitor.MdcMonitor;
import com.hyw.boot.parser.SqlParser;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.*;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 慢SQL监控拦截器
 *
 * <p>基于 MyBatis {@link Interceptor} 实现，拦截 Executor 的 query/update 方法，
 * 异步记录慢 SQL 指标，不阻塞业务主链路。</p>
 *
 * <p>MDC 上下文传递由 {@link com.hyw.boot.decorator.EnhancedMdcTaskDecorator} 统一负责，
 * 本类不再手动管理异步线程的 MDC，避免双重管理导致的上下文覆盖问题。</p>
 *
 * @author hyw
 * @version 3.0.0
 */
@Intercepts({
        @Signature(type = Executor.class, method = "update",
                args = {MappedStatement.class, Object.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}),
        @Signature(type = Executor.class, method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class})
})
public class UniversalSlowSqlInterceptor implements Interceptor, SlowSqlProperties.ConfigChangeListener {

    private static final Logger log = LoggerFactory.getLogger(UniversalSlowSqlInterceptor.class);

    /** 未能获取 sqlId 时的占位符，避免 null 字符串出现在日志和缓存 key 中 */
    private static final String UNKNOWN_SQL_ID = "unknown";

    /** Murmur3 hash 无符号最大值，用于归一化采样概率 */
    private static final double MURMUR3_MAX_UNSIGNED = 4_294_967_295.0;

    /** 熔断触发阈值：连续失败次数 */
    private static final int CIRCUIT_BREAKER_THRESHOLD = 10;
    /** 熔断恢复等待时间（ms） */
    private static final long CIRCUIT_BREAKER_RECOVERY_MS = 60_000;

    // ====== 依赖 ======
    private final SlowSqlProperties properties;
    private final SqlSensitiveFilter sensitiveFilter;
    private final SqlParser sqlParser;
    private final MeterRegistry meterRegistry;      // 可为 null（micrometer 未引入时）
    private final ApplicationContext applicationContext; // 可为 null
    private final ThreadPoolTaskExecutor asyncExecutor;
    private final MdcMonitor mdcMonitor;
    private final List<SlowSqlHandler> slowSqlHandlers;

    // ====== 熔断器状态（不可变值对象 + CAS，保证 状态/时间戳 原子切换）======
    private final AtomicReference<CircuitBreakerState> circuitState =
            new AtomicReference<>(CircuitBreakerState.CLOSED);
    /** 连续失败计数器 */
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);

    /**
     * 熔断器状态值对象（不可变）。
     *
     * <p>将 open 标志和 degradedSince 时间戳封装在同一个对象中，
     * 通过 {@link AtomicReference#compareAndSet} 一次性交换，
     * 彻底消除 "先设标志再设时间戳" 导致的竞态窗口。</p>
     */
    private static final class CircuitBreakerState {
        static final CircuitBreakerState CLOSED = new CircuitBreakerState(false, 0);
        final boolean open;
        final long degradedSince;

        CircuitBreakerState(boolean open, long degradedSince) {
            this.open = open;
            this.degradedSince = degradedSince;
        }

        static CircuitBreakerState openNow() {
            return new CircuitBreakerState(true, System.currentTimeMillis());
        }
    }

    // ====== Caffeine 缓存 ======
    /** Micrometer Timer 缓存，key = sqlId */
    private final Cache<String, Timer> timerCache;
    /** Micrometer Counter 缓存，key = sqlId */
    private final Cache<String, Counter> counterCache;
    /**
     * 采样结果缓存，key = sqlId。
     *
     * <p>同一 sqlId 的采样结果是确定性的（基于 Murmur3 hash），
     * 因此无需在 key 中拼接 sampleRate；sampleRate 变更时直接
     * {@code invalidateAll()} 即可。</p>
     */
    private final Cache<String, Boolean> sampleCache;

    // ====== 延迟初始化（PostConstruct 中赋值）======
    private SqlInfoExtractor sqlInfoExtractor;
    private SqlMetricsHandler sqlMetricsHandler;
    private DatabaseTypeDetector databaseTypeDetector;
    private SqlMonitorScheduler sqlMonitorScheduler;

    // ====================================================================
    // 构造器
    // ====================================================================

    public UniversalSlowSqlInterceptor(
            SlowSqlProperties properties,
            SqlSensitiveFilter sensitiveFilter,
            SqlParser sqlParser,
            MeterRegistry meterRegistry,
            ApplicationContext applicationContext,
            ThreadPoolTaskExecutor asyncExecutor,
            MdcMonitor mdcMonitor,
            List<SlowSqlHandler> slowSqlHandlers) {

        this.properties = properties;
        this.sensitiveFilter = sensitiveFilter;
        this.sqlParser = sqlParser;
        this.meterRegistry = meterRegistry;
        this.applicationContext = applicationContext;
        this.asyncExecutor = asyncExecutor;
        // mdcMonitor 未注入时创建默认实例，保证后续调用安全
        this.mdcMonitor = mdcMonitor != null ? mdcMonitor : new MdcMonitor(properties);
        this.slowSqlHandlers = slowSqlHandlers != null ? slowSqlHandlers : Collections.emptyList();

        int maxCacheSize = properties.getMaxCacheSize();
        this.timerCache = Caffeine.newBuilder()
                .maximumSize(maxCacheSize)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
        this.counterCache = Caffeine.newBuilder()
                .maximumSize(maxCacheSize)
                .expireAfterAccess(30, TimeUnit.MINUTES)
                .build();
        this.sampleCache = Caffeine.newBuilder()
                .maximumSize(Math.min(maxCacheSize * 5L, 5_000))
                .expireAfterWrite(30, TimeUnit.MINUTES)
                .build();
    }

    // ====================================================================
    // 生命周期
    // ====================================================================

    /**
     * 初始化内部组件。
     *
     * <p><b>重要：</b>{@link DatabaseTypeDetector} 必须先于 {@link SqlInfoExtractor}
     * 初始化，因为后者在构造时持有前者的引用。</p>
     */
    @PostConstruct
    public void init() {
        // 1. 先初始化 databaseTypeDetector
        this.databaseTypeDetector = new DatabaseTypeDetector(
                this.applicationContext,
                this.properties.getDbTypeCacheSeconds()
        );

        // 2. 再初始化依赖 databaseTypeDetector 的 sqlInfoExtractor
        this.sqlInfoExtractor = new SqlInfoExtractor(
                this.sensitiveFilter,
                this.sqlParser,
                this.properties.getMaxSqlLength(),
                this.databaseTypeDetector
        );

        // 3. 其余组件
        this.sqlMetricsHandler = new SqlMetricsHandler(
                this.properties, this.meterRegistry,
                this.timerCache, this.counterCache, this.slowSqlHandlers
        );
        this.sqlMonitorScheduler = new SqlMonitorScheduler(
                this.timerCache, this.counterCache,
                this.sampleCache, this.properties.getMaxCacheSize()
        );
        this.sqlMonitorScheduler.start();

        // 4. 注册配置变更监听
        this.properties.addListener(this);

        log.info("慢SQL监控初始化完成 - 线程池: core={}, max={}, queue={}, MDC监控={}",
                this.properties.getPool().getCoreSize(),
                this.properties.getPool().getMaxSize(),
                this.properties.getPool().getQueueCapacity(),
                this.properties.getMdc().isEnabled());
    }

    @PreDestroy
    public void destroy() {
        log.info("关闭慢SQL监控调度器...");
        if (this.sqlMonitorScheduler != null) {
            this.sqlMonitorScheduler.stop();
        }
        this.timerCache.invalidateAll();
        this.counterCache.invalidateAll();
        this.sampleCache.invalidateAll();
        log.info("慢SQL监控资源已释放");
    }

    // ====================================================================
    // MyBatis Interceptor 接口实现
    // ====================================================================

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        if (!this.properties.isEnabled()) {
            return invocation.proceed();
        }

        long start = System.nanoTime();
        // 默认值 "unknown"，防止后续拼接产生 "null" 字符串
        String sqlId = UNKNOWN_SQL_ID;
        String methodName = invocation.getMethod().getName();

        try {
            MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
            sqlId = mappedStatement.getId();
            this.sqlMonitorScheduler.incrementTotalCount();

            Object result = invocation.proceed();

            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            submitMetricsTask(invocation, sqlId, cost, null, methodName);
            return result;

        } catch (Exception e) {
            long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            submitMetricsTask(invocation, sqlId, cost, e, methodName);
            throw e;
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
        this.properties.mergeProperties(properties);
    }

    // ====================================================================
    // 配置变更监听
    // ====================================================================

    @Override
    public void onConfigChange(
            SlowSqlProperties.SlowSqlConfigSnapshot oldConfig,
            SlowSqlProperties.SlowSqlConfigSnapshot newConfig) {

        // sampleRate 变更时，采样缓存全部失效，使新 rate 立即生效
        if (oldConfig.getSampleRate() != newConfig.getSampleRate()) {
            this.sampleCache.invalidateAll();
            log.info("采样率已变更 ({} -> {})，采样缓存已清空",
                    oldConfig.getSampleRate(), newConfig.getSampleRate());
        }

        log.info("慢SQL配置已更新 - sampleRate: {} -> {}, slowThreshold: {}ms -> {}ms",
                oldConfig.getSampleRate(), newConfig.getSampleRate(),
                oldConfig.getSlowThreshold(), newConfig.getSlowThreshold());
    }

    // ====================================================================
    // 私有方法
    // ====================================================================

    /**
     * 将指标处理任务提交到异步线程池。
     *
     * <p>MDC 上下文传递由 asyncExecutor 上配置的 {@link com.hyw.boot.decorator.EnhancedMdcTaskDecorator}
     * 统一负责，无需在此手动快照和恢复 MDC。</p>
     */
    private void submitMetricsTask(
            Invocation invocation, String sqlId, long cost,
            Exception error, String methodName) {

        try {
            this.asyncExecutor.submit(() -> {
                try {
                    if (this.properties.getMdc().isEnabled()) {
                        this.mdcMonitor.recordMdcStatus();
                    }
                    processSqlMetrics(invocation, sqlId, cost, error, methodName);
                } catch (Exception ex) {
                    log.error("异步处理SQL指标失败 - sqlId: {}, error: {}", sqlId, ex.getMessage(), ex);
                }
            });
        } catch (org.springframework.core.task.TaskRejectedException e) {
            // 线程池队列已满，丢弃监控任务而非阻塞业务线程
            this.sqlMonitorScheduler.incrementRejectedCount();
        }
    }

    /**
     * 核心指标处理逻辑。
     *
     * <p>按需提取 SQL 详情（避免每次都解析 SQL 带来的性能开销）：
     * 仅在以下情况提取：超过慢 SQL 阈值、命中采样、或需要上报 Micrometer 指标。</p>
     */
    private void processSqlMetrics(
            Invocation invocation, String sqlId, long cost,
            Exception error, String methodName) {

        // 慢SQL计数始终执行（不受熔断影响）
        if (cost > this.properties.getSlowThreshold()) {
            this.sqlMonitorScheduler.incrementSlowCount();
            if (cost > this.properties.getCriticalThreshold()) {
                this.sqlMonitorScheduler.incrementCriticalCount();
            }
        }

        // 熔断检查：降级模式下跳过SQL解析/脱敏/指标上报，仅保留计数
        CircuitBreakerState currentState = this.circuitState.get();
        if (currentState.open) {
            if (System.currentTimeMillis() - currentState.degradedSince > CIRCUIT_BREAKER_RECOVERY_MS) {
                // CAS 保证只有一个线程执行恢复逻辑（整个状态对象原子替换）
                if (this.circuitState.compareAndSet(currentState, CircuitBreakerState.CLOSED)) {
                    this.consecutiveFailures.set(0);
                    log.info("熔断恢复，恢复完整SQL监控");
                }
            } else {
                return;
            }
        }

        try {
            boolean needDetail = cost > this.properties.getSlowThreshold()
                    || this.shouldSample(sqlId)
                    || (this.meterRegistry != null && this.properties.getMetrics().isEnabled());

            SqlInfo sqlInfo = needDetail
                    ? this.sqlInfoExtractor.extractSqlInfo(invocation)
                    : null;

            if (sqlInfo != null) {
                this.sqlMetricsHandler.handleSqlMetrics(sqlInfo, sqlId, cost, error, methodName);
            }

            // 成功处理，重置连续失败计数（仅在非零时重置，减少不必要的 CAS 操作）
            if (this.consecutiveFailures.get() > 0) {
                this.consecutiveFailures.set(0);
            }

        } catch (Exception e) {
            int failures = this.consecutiveFailures.incrementAndGet();
            if (failures >= CIRCUIT_BREAKER_THRESHOLD) {
                // CAS 原子交换：状态 + 时间戳一次性写入，无竞态窗口
                CircuitBreakerState expected = this.circuitState.get();
                if (!expected.open && this.circuitState.compareAndSet(expected, CircuitBreakerState.openNow())) {
                    log.error("SQL监控连续失败 {} 次，触发熔断降级（仅计时模式），将在 {}s 后尝试恢复",
                            failures, CIRCUIT_BREAKER_RECOVERY_MS / 1000);
                }
            } else {
                log.error("处理SQL指标失败 ({}/{}) - sqlId: {}, error: {}",
                        failures, CIRCUIT_BREAKER_THRESHOLD, sqlId, e.getMessage(), e);
            }
        }
    }

    /**
     * 基于 Murmur3 Hash 的确定性采样判断。
     *
     * <p>同一 sqlId 在 sampleRate 不变的情况下，采样结果恒定，
     * 避免随机采样导致同一条 SQL 时记时不记的问题。
     * 结果缓存在 {@code sampleCache} 中，sampleRate 变更时缓存会被
     * {@link #onConfigChange} 清空。</p>
     *
     * @param sqlId MyBatis MappedStatement ID
     * @return 是否应采样该条 SQL
     */
    private boolean shouldSample(String sqlId) {
        if (!this.properties.isLogEnabled()) {
            return false;
        }

        double rate = this.properties.getSampleRate();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;

        // key 只用 sqlId，sampleRate 变更时通过 invalidateAll() 使缓存失效
        return Boolean.TRUE.equals(this.sampleCache.get(sqlId, key -> {
            int hash = Hashing.murmur3_32_fixed()
                    .hashString(key, StandardCharsets.UTF_8)
                    .asInt();
            // 转为无符号长整型后归一化到 [0, 1)
            long unsignedHash = Integer.toUnsignedLong(hash);
            return (double) unsignedHash / MURMUR3_MAX_UNSIGNED < rate;
        }));
    }
}
