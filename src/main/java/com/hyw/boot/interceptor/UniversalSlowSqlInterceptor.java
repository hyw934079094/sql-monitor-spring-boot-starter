package com.hyw.boot.interceptor;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.hash.Hashing;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
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
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.ibatis.cache.CacheKey;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Interceptor;
import org.apache.ibatis.plugin.Intercepts;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.plugin.Plugin;
import org.apache.ibatis.plugin.Signature;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Component
@Intercepts({
        @Signature(
                type = Executor.class,
                method = "update",
                args = {MappedStatement.class, Object.class}
        ),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class}
        ),
        @Signature(
                type = Executor.class,
                method = "query",
                args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class, CacheKey.class, BoundSql.class}
        )
})
public class UniversalSlowSqlInterceptor implements Interceptor, SlowSqlProperties.ConfigChangeListener {
    private static final Logger log = LoggerFactory.getLogger(UniversalSlowSqlInterceptor.class);
    private final SlowSqlProperties properties;
    private final SqlSensitiveFilter sensitiveFilter;
    private final SqlParser sqlParser;
    private final MeterRegistry meterRegistry;
    private final ApplicationContext applicationContext;
    private final ThreadPoolTaskExecutor asyncExecutor;
    private final MdcMonitor mdcMonitor;
    private final Cache<String, Boolean> sampleCache;
    private final Cache<String, Timer> timerCache;
    private final Cache<String, Counter> counterCache;
    private SqlInfoExtractor sqlInfoExtractor;
    private SqlMetricsHandler sqlMetricsHandler;
    private DatabaseTypeDetector databaseTypeDetector;
    private SqlMonitorScheduler sqlMonitorScheduler;

    public UniversalSlowSqlInterceptor(SlowSqlProperties properties, SqlSensitiveFilter sensitiveFilter, SqlParser sqlParser, @Autowired(required = false) MeterRegistry meterRegistry, @Autowired(required = false) ApplicationContext applicationContext, @Qualifier("sqlMonitorExecutor") ThreadPoolTaskExecutor asyncExecutor, @Autowired(required = false) MdcMonitor mdcMonitor) {
        this.properties = properties;
        this.sensitiveFilter = sensitiveFilter;
        this.sqlParser = sqlParser;
        this.meterRegistry = meterRegistry;
        this.applicationContext = applicationContext;
        this.asyncExecutor = asyncExecutor;
        this.mdcMonitor = mdcMonitor != null ? mdcMonitor : new MdcMonitor(properties);
        this.timerCache = Caffeine.newBuilder().maximumSize((long)properties.getMaxCacheSize()).expireAfterAccess(30L, TimeUnit.MINUTES).build();
        this.counterCache = Caffeine.newBuilder().maximumSize((long)properties.getMaxCacheSize()).expireAfterAccess(30L, TimeUnit.MINUTES).build();
        this.sampleCache = Caffeine.newBuilder().maximumSize((long)Math.min(properties.getMaxCacheSize() * 5, 5000)).expireAfterWrite(30L, TimeUnit.MINUTES).build();
    }

    @PostConstruct
    public void init() {
        this.properties.addListener(this);
        this.sqlInfoExtractor = new SqlInfoExtractor(this.sensitiveFilter, this.sqlParser, this.properties.getMaxSqlLength());
        this.sqlMetricsHandler = new SqlMetricsHandler(this.properties, this.meterRegistry, this.timerCache, this.counterCache, this.sampleCache);
        this.databaseTypeDetector = new DatabaseTypeDetector(this.applicationContext, this.properties.getDbTypeCacheSeconds());
        this.sqlMonitorScheduler = new SqlMonitorScheduler(this.timerCache, this.counterCache, this.sampleCache, this.properties.getMaxCacheSize());
        this.sqlMonitorScheduler.start();
        log.info("慢SQL监控初始化完成 - 线程池: core={}, max={}, queue={}, MDC监控={}", new Object[]{this.properties.getPool().getCoreSize(), this.properties.getPool().getMaxSize(), this.properties.getPool().getQueueCapacity(), this.properties.getMdc().isEnabled()});
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

    public Object intercept(Invocation invocation) throws Throwable {
        if (!this.properties.isEnabled()) {
            return invocation.proceed();
        } else {
            long start = System.nanoTime();
            String sqlId = null;
            String methodName = invocation.getMethod().getName();

            Object var14;
            try {
                MappedStatement mappedStatement = (MappedStatement)invocation.getArgs()[0];
                sqlId = mappedStatement.getId();
                this.sqlMonitorScheduler.incrementTotalCount();

                // 移除 batch 相关逻辑
                Object result = invocation.proceed();
                long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                Map<String, String> currentContext = MDC.getCopyOfContextMap();
                String currentTraceId = MDC.get("traceId");
                String finalSqlId = sqlId;
                this.asyncExecutor.submit(() -> {
                    try {
                        this.processSqlMetricsWithMdc(invocation, finalSqlId, cost, (Exception)null, methodName, currentContext, currentTraceId);
                    } catch (Exception e) {
                        log.error("异步处理SQL指标失败: {}", e.getMessage(), e);
                    }

                });
                var14 = result;
            } catch (Exception e) {
                long cost = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
                Map<String, String> currentContext = MDC.getCopyOfContextMap();
                String currentTraceId = MDC.get("traceId");
                String finalSqlId1 = sqlId;
                this.asyncExecutor.submit(() -> {
                    try {
                        this.processSqlMetricsWithMdc(invocation, finalSqlId1, cost, e, methodName, currentContext, currentTraceId);
                    } catch (Exception ex) {
                        log.error("异步处理SQL异常指标失败: {}", ex.getMessage(), ex);
                    }

                });
                throw e;
            } finally {
                // 移除 batch 相关 MDC
            }

            return var14;
        }
    }

    private void processSqlMetricsWithMdc(Invocation invocation, String sqlId, long cost, Exception error, String methodName, Map<String, String> context, String traceId) {
        if (context != null && !context.isEmpty()) {
            MDC.setContextMap(context);
        }

        try {
            if (this.mdcMonitor != null && this.properties.getMdc().isEnabled()) {
                this.mdcMonitor.recordMdcStatus();
            }

            this.processSqlMetrics(invocation, sqlId, cost, error, methodName);
        } finally {
            MDC.clear();
        }

    }

    private void processSqlMetrics(Invocation invocation, String sqlId, long cost, Exception error, String methodName) {
        try {
            SqlInfo sqlInfo = null;
            boolean needDetail = cost > this.properties.getSlowThreshold() || this.shouldSample(sqlId) || this.meterRegistry != null;
            if (needDetail) {
                sqlInfo = this.sqlInfoExtractor.extractSqlInfo(invocation);
            }

            if (cost > this.properties.getSlowThreshold()) {
                this.sqlMonitorScheduler.incrementSlowCount();
                if (cost > this.properties.getCriticalThreshold()) {
                    this.sqlMonitorScheduler.incrementCriticalCount();
                }
            }

            if (sqlInfo != null) {
                this.sqlMetricsHandler.handleSqlMetrics(sqlInfo, sqlId, cost, error, methodName);
            }
        } catch (Exception e) {
            log.error("处理SQL指标失败 - sqlId: {}, error: {}", new Object[]{sqlId, e.getMessage(), e});
        }

    }

    private boolean shouldSample(String sqlId) {
        if (!this.properties.isLogEnabled()) {
            return false;
        } else {
            double rate = this.properties.getSampleRate();
            if (rate >= (double)1.0F) {
                return true;
            } else if (rate <= (double)0.0F) {
                return false;
            } else {
                String cacheKey = sqlId + ":" + String.format("%.6f", rate);
                return (Boolean)this.sampleCache.get(cacheKey, (key) -> {
                    int hash = Hashing.murmur3_32().hashString(sqlId, StandardCharsets.UTF_8).asInt();
                    long unsignedHash = (long)hash & 4294967295L;
                    return (double)unsignedHash / (double)4.2949673E9F < rate ? true : false;
                });
            }
        }
    }

    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    public void setProperties(Properties properties) {
        this.properties.mergeProperties(properties);
    }

    public void onConfigChange(SlowSqlProperties.SlowSqlConfigSnapshot oldConfig, SlowSqlProperties.SlowSqlConfigSnapshot newConfig) {
        if (oldConfig.getSampleRate() != newConfig.getSampleRate()) {
            this.sampleCache.invalidateAll();
        }

        log.info("配置已更新 - sampleRate: {} -> {}, slowThreshold: {}ms -> {}ms", new Object[]{oldConfig.getSampleRate(), newConfig.getSampleRate(), oldConfig.getSlowThreshold(), newConfig.getSlowThreshold()});
    }
}