package com.hyw.boot.filter;

import com.hyw.boot.config.SlowSqlProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * SQL脱敏过滤器
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlSensitiveFilter {

    private static final Logger log = LoggerFactory.getLogger(SqlSensitiveFilter.class);

    private final SlowSqlProperties properties;

    // 正则
    private Pattern sensitiveFieldPattern;
    private Pattern sensitiveTablePattern;
    private final Pattern phonePattern = Pattern.compile("(1[3-9]\\d{9})");
    private final Pattern idCardPattern = Pattern.compile("[1-9]\\d{5}(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])\\d{3}[\\dXx]");

    public SqlSensitiveFilter(SlowSqlProperties properties) {
        this.properties = properties;
    }

    /**
     * 初始化正则（启动时执行一次）
     */
    @PostConstruct
    public void initPattern() {
        log.debug("=== SQL脱敏过滤器初始化开始 ===");

        SlowSqlProperties.SensitiveConfig sensitive = properties.getSensitive();

        if (sensitive == null || !sensitive.isEnabled()) {
            return;
        }

        // 敏感字段正则（支持单引号/双引号），统一使用 CASE_INSENSITIVE 标志
        String fieldRegex = sensitive.getSensitiveFields()
                .stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));

        this.sensitiveFieldPattern = Pattern.compile(
                "(" + fieldRegex + ")\\s*=\\s*(['\"])(.*?)\\2",
                Pattern.CASE_INSENSITIVE
        );

        // 敏感表正则
        String tableRegex = sensitive.getSensitiveTables()
                .stream()
                .map(Pattern::quote)
                .collect(Collectors.joining("|"));

        this.sensitiveTablePattern = Pattern.compile(
                "\\b(from|into|update|join)\\s+(" + tableRegex + ")\\b",
                Pattern.CASE_INSENSITIVE
        );
        log.debug("=== SQL脱敏过滤器初始化完成 ===");
    }

    /**
     * 脱敏入口
     */
    public String filter(String sql) {
        // 关闭状态直接返回
        if (!properties.getSensitive().isEnabled() || !StringUtils.hasText(sql)) {
            return sql;
        }

        try {
            log.debug("开始脱敏SQL：{}", sql);

            boolean isSensitiveTable = isSensitiveTable(sql);
            String filtered;

            if (isSensitiveTable) {
                log.debug("检测到敏感表操作，执行全脱敏");
                filtered = maskSensitiveTableData(sql);
            } else {
                filtered = filterSensitiveFields(sql);
                filtered = filterPhoneNumbers(filtered);
                filtered = filterIdCards(filtered);
            }

            if (!sql.equals(filtered)) {
                log.debug("SQL脱敏完成：{}", filtered);
            }

            return filtered;

        } catch (Exception e) {
            log.error("SQL脱敏异常", e);
            return sql;
        }
    }


    /**
     * 对象脱敏
     */
    public String filter(Object obj) {
        if (obj == null) return "null";
        return filter(Objects.toString(obj, "null"));
    }

    /**
     * 敏感字段脱敏：password='xxx' → password='****'
     */
    private String filterSensitiveFields(String sql) {
        if (sensitiveFieldPattern == null) return sql;

        Matcher matcher = sensitiveFieldPattern.matcher(sql);
        StringBuilder sb = new StringBuilder();

        while (matcher.find()) {
            String field = matcher.group(1);
            String quote = matcher.group(2);
            String value = matcher.group(3);
            String masked = maskValue(value);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(field + "=" + quote + masked + quote));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 判断是否是敏感表
     */
    private boolean isSensitiveTable(String sql) {
        if (sensitiveTablePattern == null) return false;
        return sensitiveTablePattern.matcher(sql).find();
    }

    /**
     * 敏感表全脱敏：对 VALUES、SET、WHERE 中的值进行脱敏
     */
    private String maskSensitiveTableData(String sql) {
        String result = sql;
        // INSERT VALUES 脱敏
        result = result.replaceAll("(?i)values\\s*\\([^)]*\\)", "values(***)");
        // SET 子句中的值脱敏
        result = result.replaceAll("(?i)set\\s+(.+?)(?=\\s+where|$)", "SET ***");
        // WHERE 条件中的字面值脱敏
        result = result.replaceAll("=\\s*(['\"]).*?\\1", "='***'");
        return result;
    }

    /**
     * 手机号脱敏
     */
    private String filterPhoneNumbers(String sql) {
        Matcher matcher = phonePattern.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String phone = matcher.group();
            String masked = phone.substring(0, 3) + "****" + phone.substring(7);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 身份证脱敏
     */
    private String filterIdCards(String sql) {
        Matcher matcher = idCardPattern.matcher(sql);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String id = matcher.group();
            String masked = id.substring(0, 6) + "********" + id.substring(14);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(masked));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 通用脱敏工具
     */
    private String maskValue(String value) {
        if (!StringUtils.hasText(value)) return "****";

        int maskLength = properties.getSensitive().getMaskLength();
        String maskChar = properties.getSensitive().getMaskChar();
        String mask = maskChar.repeat(4);

        if (value.length() <= maskLength * 2) {
            // 值太短，无法同时展示前后缀，直接全部脱敏
            return mask;
        }

        String prefix = value.substring(0, maskLength);
        String suffix = value.substring(value.length() - maskLength);
        return prefix + mask + suffix;
    }
}
