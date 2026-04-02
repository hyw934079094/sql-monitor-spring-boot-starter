package com.hyw.boot.interceptor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.google.common.hash.Hashing;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.model.SlowSqlLog;
import com.hyw.boot.model.SqlInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * SQL指标处理器
 * 负责处理SQL执行指标和慢SQL告警
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlMetricsHandler {

    private static final Logger log = LoggerFactory.getLogger(SqlMetricsHandler.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SlowSqlProperties properties;
    private final MeterRegistry meterRegistry;
    private final Cache<String, Timer> timerCache;
    private final Cache<String, Counter> counterCache;
    private final Cache<String, Boolean> sampleCache;

    public SqlMetricsHandler(SlowSqlProperties properties, MeterRegistry meterRegistry,
                             Cache<String, Timer> timerCache, Cache<String, Counter> counterCache,
                             Cache<String, Boolean> sampleCache) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.timerCache = timerCache;
        this.counterCache = counterCache;
        this.sampleCache = sampleCache;
    }

    /**
     * 处理SQL执行指标
     */
    public void handleSqlMetrics(SqlInfo sqlInfo, String sqlId, long cost, Exception error, String methodName) {
        if (cost > properties.getSlowThreshold()) {
            handleSlowSql(sqlInfo, sqlId, cost, error, methodName);
        }

        if (shouldSample(sqlId)) {
            log.debug("SQL执行 - {} - {}ms, 类型: {}, 表: {}",
                    sqlId, cost, sqlInfo.getSqlType(), sqlInfo.getTables());
        }

        recordMetrics(sqlInfo, cost);
    }

    /**
     * 处理慢SQL
     */
    private void handleSlowSql(SqlInfo sqlInfo, String sqlId, long cost, Exception error, String methodName) {
        SlowSqlLog logEntry = SlowSqlLog.builder()
                .timestamp(System.currentTimeMillis())
                .threadName(Thread.currentThread().getName())
                .sqlId(sqlId)
                .cost(cost)
                .method(methodName)
                .sqlType(sqlInfo.getSqlType())
                .tables(sqlInfo.getTables())
                .sql(sqlInfo.getFilteredSql())
                .params(sqlInfo.getParams())
                .hasJoin(sqlInfo.isHasJoin())
                .hasSubQuery(sqlInfo.isHasSubQuery())
                .whereCondition(sqlInfo.getWhereCondition())
                .hasError(error != null)
                .errorMessage(error != null ? error.getMessage() : null)
                .traceId(MDC.get("traceId"))
                .userId(MDC.get("userId"))
                .build();

        if (cost > properties.getCriticalThreshold()) {
            log.error("严重慢SQL告警: {}", toJson(logEntry));
        } else {
            log.warn("慢SQL记录: {}", toJson(logEntry));
        }
    }

    /**
     * 判断是否需要采样
     */
    private boolean shouldSample(String sqlId) {
        if (!properties.isLogEnabled()) return false;
        double rate = properties.getSampleRate();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;

        String cacheKey = sqlId + ":" + String.format("%.6f", rate);

        return sampleCache.get(cacheKey, key -> {
            int hash = Hashing.murmur3_32().hashString(sqlId, StandardCharsets.UTF_8).asInt();
            long unsignedHash = hash & 0xFFFFFFFFL;
            return unsignedHash / (double) (1L << 32) < rate;
        });
    }

    /**
     * 记录监控指标
     */
    private void recordMetrics(SqlInfo sqlInfo, long cost) {
        if (meterRegistry == null) return;

        String timerKey = sqlInfo.getSqlId() + "_" + String.join(",", sqlInfo.getTables());

        Timer timer = timerCache.get(timerKey, k ->
                Timer.builder("sql.execution.time")
                        .tags(
                                "sqlId", sqlInfo.getSqlId(),
                                "sqlType", sqlInfo.getSqlType(),
                                "tables", String.join(",", sqlInfo.getTables())
                        )
                        .publishPercentiles(0.5, 0.95, 0.99)
                        .register(meterRegistry)
        );
        timer.record(Duration.ofMillis(cost));

        if (cost > properties.getSlowThreshold()) {
            String slowKey = "slow_" + sqlInfo.getSqlId();
            Counter slowCounter = counterCache.get(slowKey, k ->
                    Counter.builder("sql.slow.count")
                            .tags("sqlId", sqlInfo.getSqlId())
                            .register(meterRegistry)
            );
            slowCounter.increment();
        }
    }

    /**
     * 对象转JSON字符串
     */
    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
