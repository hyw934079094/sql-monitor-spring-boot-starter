package com.hyw.boot.interceptor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.model.SqlInfo;
import com.hyw.boot.parser.SqlParser;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL信息提取器（支持自动数据库类型适配）
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlInfoExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlInfoExtractor.class);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    /** MyBatis 自动生成的位置参数名（param1, param2, ...），在格式化时过滤 */
    private static final Pattern MYBATIS_PARAM_PATTERN = Pattern.compile("param\\d+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final SqlSensitiveFilter sensitiveFilter;
    private final SqlParser sqlParser;
    private final SlowSqlProperties properties;
    private final DatabaseTypeDetector databaseTypeDetector;

    public SqlInfoExtractor(SqlSensitiveFilter sensitiveFilter,
                            SqlParser sqlParser,
                            SlowSqlProperties properties,
                            DatabaseTypeDetector databaseTypeDetector) {
        this.sensitiveFilter = sensitiveFilter;
        this.sqlParser = sqlParser;
        this.properties = properties;
        this.databaseTypeDetector = databaseTypeDetector;
    }

    /**
     * 从Invocation中提取SQL信息
     */
    public SqlInfo extractSqlInfo(Invocation invocation) {
        SqlInfo.SqlInfoBuilder builder = SqlInfo.builder();

        try {
            MappedStatement mappedStatement = (MappedStatement) invocation.getArgs()[0];
            Object parameter = invocation.getArgs().length > 1 ? invocation.getArgs()[1] : null;

            BoundSql boundSql = mappedStatement.getBoundSql(parameter);
            String sql = boundSql.getSql();

            // 自动获取数据库类型并适配SQL格式
            String dbType = databaseTypeDetector.getDatabaseType();
            sql = adaptSqlByDbType(sql, dbType);

            // 解析SQL（超长 SQL 跳过解析，防止解析器挂起）
            int sqlParseMaxLength = properties.getSqlParseMaxLength();
            SqlParser.SqlParseResult parseResult;
            if (sql.length() > sqlParseMaxLength) {
                log.debug("SQL 长度 {} 超过解析上限 {}，跳过解析", sql.length(), sqlParseMaxLength);
                parseResult = new SqlParser.SqlParseResult();
                parseResult.setOriginalSql(sql);
                parseResult.setSqlType(inferSqlTypeByPrefix(sql));
            } else {
                parseResult = sqlParser.parse(sql);
            }

            // 脱敏
            String filteredSql = sensitiveFilter.filter(sql);
            filteredSql = WHITESPACE_PATTERN.matcher(filteredSql).replaceAll(" ").trim();

            // 超长截取
            int maxLen = properties.getMaxSqlLength();
            if (filteredSql.length() > maxLen) {
                filteredSql = filteredSql.substring(0, maxLen) + "...";
            }

            builder.sqlId(mappedStatement.getId())
                    .filteredSql(filteredSql)
                    .sqlType(parseResult.getSqlType())
                    .tables(parseResult.getTables())
                    .whereCondition(parseResult.getWhereCondition())
                    .hasJoin(parseResult.isHasJoin())
                    .hasSubQuery(parseResult.isHasSubQuery())
                    .params(formatParameter(parameter, boundSql))
                    .dbType(dbType)
                    .traceId(MDC.get("traceId"))
                    .userId(MDC.get("userId"));

        } catch (Exception e) {
            log.debug("提取SQL信息失败: {}", e.getMessage());
            builder.error(e.getMessage());
        }

        return builder.build();
    }

    /**
     * 通过 SQL 前缀关键字快速推断 SQL 类型（用于超长 SQL 跳过完整解析时的降级方案）
     */
    private String inferSqlTypeByPrefix(String sql) {
        String trimmed = sql.stripLeading();
        if (trimmed.length() < 6) return "UNKNOWN";
        String prefix = trimmed.substring(0, 6).toUpperCase();
        if (prefix.startsWith("SELECT")) return "SELECT";
        if (prefix.startsWith("INSERT")) return "INSERT";
        if (prefix.startsWith("UPDATE")) return "UPDATE";
        if (prefix.startsWith("DELETE")) return "DELETE";
        if (prefix.startsWith("MERGE")) return "MERGE";
        return "UNKNOWN";
    }

    /**
     * 数据库类型自动适配SQL（用于监控展示，不影响实际执行）
     */
    private String adaptSqlByDbType(String sql, String dbType) {
        if (dbType == null) {
            return sql;
        }
        return switch (dbType.toLowerCase()) {
            case "oracle" ->
                // 移除Oracle双引号包裹（仅用于监控展示）
                    sql.replace("\"", "");
            case "dm" ->
                // 达梦数据库类似Oracle处理
                    sql.replace("\"", "");
            default -> sql;
        };
    }

    /**
     * 格式化参数（含敏感字段脱敏）
     */
    @SuppressWarnings("unchecked")
    private String formatParameter(Object parameter, BoundSql boundSql) {
        try {
            if (parameter == null) {
                return "null";
            }

            if (parameter instanceof Map) {
                return formatMapParameter((Map<String, Object>) parameter);
            }

            if (parameter instanceof Collection) {
                Collection<?> collection = (Collection<?>) parameter;
                if (collection.size() > 50) {
                    return "Collection(size=" + collection.size() + ")";
                }
                String result = collection.stream()
                        .map(this::safeObjectToString)
                        .collect(Collectors.joining(", ", "[", "]"));
                return desensitizeParamString(result);
            }

            return desensitizeParamString(safeObjectToString(parameter));
        } catch (Exception e) {
            return "参数格式化失败: " + e.getMessage();
        }
    }

    /**
     * 格式化Map参数（过滤mybatis内部参数，敏感字段值脱敏）
     */
    private String formatMapParameter(Map<String, Object> map) {
        if (map.size() > 50) {
            return "Map(size=" + map.size() + ")";
        }

        Map<String, String> filtered = new HashMap<>();
        map.forEach((key, value) -> {
            // 过滤mybatis自动生成的参数名(param1, param2...)
            if (key != null && !MYBATIS_PARAM_PATTERN.matcher(key).matches()) {
                // 敏感字段值脱敏
                if (sensitiveFilter.isSensitiveField(key)) {
                    filtered.put(key, "****");
                } else {
                    filtered.put(key, safeObjectToString(value));
                }
            }
        });

        try {
            return OBJECT_MAPPER.writeValueAsString(filtered);
        } catch (Exception e) {
            return filtered.toString();
        }
    }

    /**
     * 安全的对象转字符串（防止超长）
     */
    private String safeObjectToString(Object obj) {
        if (obj == null) {
            return "null";
        }
        if (obj instanceof String) {
            String str = (String) obj;
            return str.length() > 200 ? str.substring(0, 200) + "..." : str;
        }
        String str = obj.toString();
        return str.length() > 100 ? str.substring(0, 100) + "..." : str;
    }

    /**
     * 对参数字符串执行手机号/身份证脱敏（复用 SqlSensitiveFilter 的能力）
     */
    private String desensitizeParamString(String paramStr) {
        if (paramStr == null || !properties.getSensitive().isEnabled()) {
            return paramStr;
        }
        return sensitiveFilter.filter(paramStr);
    }
}
