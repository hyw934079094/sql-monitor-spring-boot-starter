package com.hyw.boot.interceptor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.benmanes.caffeine.cache.Cache;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.model.SlowSqlLog;
import com.hyw.boot.model.SqlInfo;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Collections;
import java.util.List;

/**
 * SQL 指标处理器（支持数据库类型）
 *
 * <p>主要优化点：</p>
 * <ul>
 *   <li>删除重复的 shouldSample 方法，采样判断统一由调用方（拦截器）控制</li>
 *   <li>修复 Timer tag 高基数问题，tables 截断后再作为 tag 值</li>
 *   <li>修复 timerKey / slowKey 拼接时 null 导致的缓存 key 污染</li>
 *   <li>traceId/userId 从 SqlInfo 读取而非直接读 MDC，解耦隐式依赖</li>
 *   <li>ObjectMapper 增加安全配置，与 SqlInfoExtractor 保持一致</li>
 *   <li>提取常量，消除魔法数字</li>
 * </ul>
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlMetricsHandler {

    private static final Logger log = LoggerFactory.getLogger(SqlMetricsHandler.class);

    /**
     * Micrometer tag 中 tables 值的最大字符长度。
     * 超出时截断，避免高基数（High Cardinality）导致时间序列爆炸。
     */
    private static final int TABLES_TAG_MAX_LENGTH = 50;

    /** timerKey / slowKey 中 dbType 为 null 时的占位符 */
    private static final String UNKNOWN_DB_TYPE = "unknown";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    // ====== 依赖 ======
    private final SlowSqlProperties properties;
    private final MeterRegistry meterRegistry;   // 可为 null
    /**
     * Timer/Counter 缓存仅作为性能优化，避免每次查询 MeterRegistry 的开销。
     * Caffeine 驱逐条目后，下次 get() 会重新调用 Timer.builder().register()，
     * Micrometer 的 register() 方法是幂等的——同名同 tag 会返回已存在的 Meter，
     * 不会产生重复指标或计数器重置。
     */
    private final Cache<String, Timer> timerCache;
    private final Cache<String, Counter> counterCache;
    private final List<SlowSqlHandler> slowSqlHandlers;

    public SqlMetricsHandler(SlowSqlProperties properties,
                             MeterRegistry meterRegistry,
                             Cache<String, Timer> timerCache,
                             Cache<String, Counter> counterCache,
                             List<SlowSqlHandler> slowSqlHandlers) {
        this.properties = properties;
        this.meterRegistry = meterRegistry;
        this.timerCache = timerCache;
        this.counterCache = counterCache;
        this.slowSqlHandlers = slowSqlHandlers != null ? slowSqlHandlers : Collections.emptyList();
    }

    // ====================================================================
    // 公开方法
    // ====================================================================

    /**
     * 处理 SQL 执行指标。
     *
     * <p>调用方已完成采样判断，本方法不再重复做 shouldSample 判断，
     * 直接处理慢 SQL 告警和 Micrometer 指标上报。</p>
     *
     * @param sqlInfo    SQL 详情（含 traceId、userId 等链路信息）
     * @param sqlId      MyBatis MappedStatement ID
     * @param cost       执行耗时（毫秒）
     * @param error      执行异常，正常执行时为 null
     * @param methodName MyBatis Executor 方法名（query / update）
     */
    public void handleSqlMetrics(SqlInfo sqlInfo, String sqlId,
                                 long cost, Exception error, String methodName) {
        if (cost > properties.getSlowThreshold()) {
            handleSlowSql(sqlInfo, sqlId, cost, error, methodName);
        }

        // 采样日志：由调用方 shouldSample 判断后决定是否调用本方法，
        // 此处仅在 debug 级别输出轻量摘要
        if (log.isDebugEnabled()) {
            log.debug("SQL执行 - {} - {}ms, 类型: {}, 表: {}, 数据库: {}",
                    sqlId, cost, sqlInfo.getSqlType(),
                    sqlInfo.getTables(), sqlInfo.getDbType());
        }

        recordMetrics(sqlInfo, cost);
    }

    // ====================================================================
    // 私有方法 —— 慢 SQL 处理
    // ====================================================================

    /**
     * 构造慢 SQL 日志并按严重级别输出。
     *
     * <p>traceId / userId 从 {@link SqlInfo} 读取，而非直接调用 {@code MDC.get()}，
     * 避免对 MDC 上下文的隐式依赖（SqlInfo 在提取阶段已将链路信息封装进来）。</p>
     */
    private void handleSlowSql(SqlInfo sqlInfo, String sqlId,
                               long cost, Exception error, String methodName) {
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
                // 从 SqlInfo 读取，解耦 MDC 隐式依赖
                .traceId(sqlInfo.getTraceId())
                .userId(sqlInfo.getUserId())
                .dbType(sqlInfo.getDbType())
                .build();

        if (cost > properties.getCriticalThreshold()) {
            log.error("严重慢SQL告警: {}", toJson(logEntry));
        } else {
            log.warn("慢SQL记录: {}", toJson(logEntry));
        }

        // 回调所有 SlowSqlHandler 扩展（持久化到 DB/ES/MQ 等）
        for (SlowSqlHandler handler : slowSqlHandlers) {
            try {
                handler.handle(logEntry);
            } catch (Exception e) {
                log.warn("SlowSqlHandler [{}] 执行失败: {}",
                        handler.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    // ====================================================================
    // 私有方法 —— Micrometer 指标上报
    // ====================================================================

    /**
     * 向 Micrometer 上报 SQL 执行时间和慢 SQL 计数。
     *
     * <p><b>高基数问题说明：</b>Micrometer 的 tag 值会成为监控系统（Prometheus/InfluxDB）
     * 的时间序列维度，表名组合理论上无界。因此 tables tag 值在超出
     * {@value #TABLES_TAG_MAX_LENGTH} 字符时截断，防止时间序列爆炸。</p>
     */
    private void recordMetrics(SqlInfo sqlInfo, long cost) {
        if (meterRegistry == null) return;

        SlowSqlProperties.MetricsConfig metricsConfig = properties.getMetrics();
        if (!metricsConfig.isEnabled()) return;

        String dbType = resolveDbType(sqlInfo.getDbType());
        String tablesTag = truncateTablesTag(sqlInfo.getTables());
        String sqlType = sqlInfo.getSqlType() != null ? sqlInfo.getSqlType() : "unknown";
        String resolvedSqlId = sqlInfo.getSqlId() != null ? sqlInfo.getSqlId() : "unknown";
        boolean includeSqlId = metricsConfig.isIncludeSqlId();

        // 缓存 key：根据配置决定是否包含 sqlId，降低时间序列基数
        String timerKey = includeSqlId
                ? resolvedSqlId + "_" + tablesTag + "_" + dbType
                : sqlType + "_" + tablesTag + "_" + dbType;

        Timer timer = timerCache.get(timerKey, k -> {
            Timer.Builder builder = Timer.builder("sql.execution.time")
                    .tags("sqlType", sqlType, "tables", tablesTag, "dbType", dbType);
            if (includeSqlId) {
                builder.tag("sqlId", resolvedSqlId);
            }
            if (metricsConfig.isPercentileHistogram()) {
                builder.publishPercentileHistogram();
            } else {
                builder.publishPercentiles(metricsConfig.getClientPercentiles());
            }
            return builder.register(meterRegistry);
        });
        timer.record(Duration.ofMillis(cost));

        if (cost > properties.getSlowThreshold()) {
            String slowKey = includeSqlId
                    ? "slow_" + resolvedSqlId + "_" + dbType
                    : "slow_" + sqlType + "_" + dbType;
            Counter slowCounter = counterCache.get(slowKey, k -> {
                Counter.Builder builder = Counter.builder("sql.slow.count")
                        .tags("sqlType", sqlType, "dbType", dbType);
                if (includeSqlId) {
                    builder.tag("sqlId", resolvedSqlId);
                }
                return builder.register(meterRegistry);
            });
            slowCounter.increment();
        }
    }

    // ====================================================================
    // 私有工具方法
    // ====================================================================

    /**
     * 将表名列表拼接为 tag 字符串，超长时截断，防止高基数。
     */
    private String truncateTablesTag(List<String> tables) {
        if (tables == null || tables.isEmpty()) return "unknown";
        String joined = String.join(",", tables);
        if (joined.length() > TABLES_TAG_MAX_LENGTH) {
            return joined.substring(0, TABLES_TAG_MAX_LENGTH) + "~";
        }
        return joined;
    }

    /**
     * dbType 为 null 时返回占位符，防止缓存 key 出现 "null" 字符串。
     */
    private String resolveDbType(String dbType) {
        return dbType != null ? dbType : UNKNOWN_DB_TYPE;
    }

    private String toJson(Object obj) {
        try {
            return OBJECT_MAPPER.writeValueAsString(obj);
        } catch (Exception e) {
            return obj.toString();
        }
    }
}
