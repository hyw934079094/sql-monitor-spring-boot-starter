package com.hyw.boot.interceptor.handler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.model.SqlInfo;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.BoundSql;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.plugin.Invocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * SQL信息提取器
 * 负责从Invocation中提取SQL信息
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlInfoExtractor {

    private static final Logger log = LoggerFactory.getLogger(SqlInfoExtractor.class);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final SqlSensitiveFilter sensitiveFilter;
    private final SqlParser sqlParser;
    private final int maxSqlLength;

    public SqlInfoExtractor(SqlSensitiveFilter sensitiveFilter, SqlParser sqlParser, int maxSqlLength) {
        this.sensitiveFilter = sensitiveFilter;
        this.sqlParser = sqlParser;
        this.maxSqlLength = maxSqlLength;
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

            JSqlParser.SqlParseResult parseResult = sqlParser.parse(sql);

            String filteredSql = sensitiveFilter.filter(sql);
            filteredSql = WHITESPACE_PATTERN.matcher(filteredSql).replaceAll(" ").trim();

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
                    .hasSubQuery(parseResult.isHasSubQuery());

        } catch (Exception e) {
            log.debug("提取SQL信息失败: {}", e.getMessage());
            builder.error(e.getMessage());
        }

        return builder.build();
    }

    /**
     * 格式化参数
     */
    private String formatParameter(Object parameter) {
        if (parameter == null) return "null";

        try {
            if (parameter instanceof Map) {
                Map<?, ?> map = (Map<?, ?>) parameter;
                // 快速路径：如果Map太大，直接返回大小信息
                if (map.size() > 50) {
                    return String.format("Map(size=%d)", map.size());
                }
                Map<String, Object> filteredMap = new HashMap<>(map.size());
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    String key = String.valueOf(entry.getKey());
                    if (!key.startsWith("param") && !key.matches("param\\d+")) {
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
            // 对于常见类型，直接处理
            if (obj instanceof String) {
                String str = (String) obj;
                if (str.length() > 200) {
                    str = str.substring(0, 200) + "...";
                }
                return sensitiveFilter.filter(str);
            }
            // 对于数字、布尔等基本类型包装类，直接返回
            if (obj instanceof Number || obj instanceof Boolean) {
                return obj.toString();
            }
            // 对于其他类型，使用toString()方法
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
