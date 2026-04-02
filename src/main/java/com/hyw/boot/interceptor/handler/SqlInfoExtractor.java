package com.hyw.boot.interceptor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
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

/**
 * SQL信息提取器（支持自动数据库类型适配）
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlInfoExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlInfoExtractor.class);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final SqlSensitiveFilter sensitiveFilter;
    private final SqlParser sqlParser;
    private final int maxSqlLength;
    private final DatabaseTypeDetector databaseTypeDetector;

    public SqlInfoExtractor(SqlSensitiveFilter sensitiveFilter,
                            SqlParser sqlParser,
                            int maxSqlLength,
                            DatabaseTypeDetector databaseTypeDetector) {
        this.sensitiveFilter = sensitiveFilter;
        this.sqlParser = sqlParser;
        this.maxSqlLength = maxSqlLength;
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

            if ("batch".equals(invocation.getMethod().getName()) && parameter instanceof Collection) {
                Collection<?> params = (Collection<?>) parameter;
                if (!params.isEmpty()) {
                    parameter = params.iterator().next();
                }
            }

            BoundSql boundSql = mappedStatement.getBoundSql(parameter);
            String sql = boundSql.getSql();

            // 自动获取数据库类型并适配SQL格式
            String dbType = databaseTypeDetector.getDatabaseType();
            sql = adaptSqlByDbType(sql, dbType);

            // 解析SQL
            SqlParser.SqlParseResult parseResult = sqlParser.parse(sql);

            // 脱敏
            String filteredSql = sensitiveFilter.filter(sql);
            filteredSql = WHITESPACE_PATTERN.matcher(filteredSql).replaceAll(" ").trim();

            // 超长截取
            if (filteredSql.length() > maxSqlLength) {
                filteredSql = filteredSql.substring(0, maxSqlLength) + "...";
            }

            builder.sqlId(mappedStatement.getId())
                    .filteredSql(filteredSql)
                    .sqlType(parseResult.getSqlType())
                    .tables(parseResult.getTables())
                    .whereCondition(parseResult.getWhereCondition())
                    .params(formatParameter(parameter))
                    .hasJoin(parseResult.isHasJoin())
                    .hasSubQuery(parseResult.isHasSubQuery())
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
            case "postgresql" ->
                // PG适配（移除多余换行）
                    sql.trim();
            case "sqlserver" ->
                // SQL Server适配
                    sql.replace("[", "").replace("]", "");
            default -> sql;
        };
    }

    /**
     * 格式化参数
     */
    private String formatParameter(Object parameter) {
        if (parameter == null) return "null";

        try {
            if (parameter instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parameter;
                if (map.size() > 50) {
                    return String.format("Map(size=%d)", map.size());
                }
                Map<String, Object> filteredMap = new HashMap<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    // 过滤MyBatis自动生成的 param1, param2 等重复参数
                    if (!key.startsWith("param")) {
                        filteredMap.put(key, safeObjectToString(entry.getValue()));
                    }
                }
                return OBJECT_MAPPER.writeValueAsString(filteredMap);
            }

            if (parameter instanceof Collection) {
                Collection<?> collection = (Collection<?>) parameter;
                if (collection.isEmpty()) return "empty collection";
                if (collection.size() > 10) {
                    return String.format("Collection(size=%d, first: %s...)",
                            collection.size(), safeObjectToString(collection.iterator().next()));
                }
                return OBJECT_MAPPER.writeValueAsString(collection);
            }

            if (parameter.getClass().isArray()) {
                int length = java.lang.reflect.Array.getLength(parameter);
                if (length == 0) return "empty array";
                if (length > 10) {
                    return String.format("Array(length=%d, first: %s...)",
                            length, safeObjectToString(java.lang.reflect.Array.get(parameter, 0)));
                }
                return OBJECT_MAPPER.writeValueAsString(parameter);
            }

            return sensitiveFilter.filter(parameter.toString());

        } catch (Exception e) {
            return String.format("参数格式化失败: %s", e.getMessage());
        }
    }

    /**
     * 安全的对象转字符串
     */
    private String safeObjectToString(Object obj) {
        if (obj == null) return "null";
        try {
            if (obj instanceof String) {
                String str = (String) obj;
                if (str.length() > 200) {
                    str = str.substring(0, 200) + "...";
                }
                return sensitiveFilter.filter(str);
            }
            if (obj instanceof Number || obj instanceof Boolean) {
                return obj.toString();
            }
            String str = obj.toString();
            if (str.length() > 100) {
                str = str.substring(0, 100) + "...";
            }
            return sensitiveFilter.filter(str);
        } catch (Exception e) {
            return obj.getClass().getSimpleName() + "@" + Integer.toHexString(obj.hashCode());
        }
    }
}
