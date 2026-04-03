package com.hyw.boot.interceptor.handler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 数据库类型检测器
 * 负责检测和缓存数据库类型
 *
 * @author hyw
 * @version 3.0.0
 */
public class DatabaseTypeDetector {

    private static final Logger log = LoggerFactory.getLogger(DatabaseTypeDetector.class);
    private static final Pattern DB_TYPE_PATTERN = Pattern.compile(
            "jdbc:(mysql|oracle|dm|postgresql|sqlserver):.*", Pattern.CASE_INSENSITIVE);

    private final ApplicationContext applicationContext;
    private final int dbTypeCacheSeconds;

    private volatile DatabaseTypeCache dbTypeCache;

    public DatabaseTypeDetector(ApplicationContext applicationContext, int dbTypeCacheSeconds) {
        this.applicationContext = applicationContext;
        this.dbTypeCacheSeconds = dbTypeCacheSeconds;
    }

    /**
     * 获取数据库类型
     */
    public String getDatabaseType() {
        DatabaseTypeCache cache = this.dbTypeCache;
        if (cache != null && System.currentTimeMillis() - cache.fetchTime <
                dbTypeCacheSeconds * 1000L) {
            return cache.dbType;
        }
        return refreshDbTypeCache();
    }

    /**
     * 刷新数据库类型缓存
     */
    public synchronized String refreshDbTypeCache() {
        String dbType = doGetDatabaseType();
        this.dbTypeCache = new DatabaseTypeCache(dbType, System.currentTimeMillis());
        return dbType;
    }

    /**
     * 实际获取数据库类型
     */
    private String doGetDatabaseType() {
        if (applicationContext != null) {
            try {
                // 使用 getBeanProvider 替代 getBean：多数据源时自动选取 @Primary Bean，
                // 无 @Primary 时返回 null（而非抛 NoUniqueBeanDefinitionException）
                org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory =
                        applicationContext.getBeanProvider(org.apache.ibatis.session.SqlSessionFactory.class)
                                .getIfAvailable();
                if (sqlSessionFactory != null) {
                    String dbType = sqlSessionFactory.getConfiguration().getDatabaseId();
                    if (dbType != null) return dbType;
                }
            } catch (Exception ignored) {}

            try {
                javax.sql.DataSource dataSource = applicationContext
                        .getBeanProvider(javax.sql.DataSource.class).getIfAvailable();
                if (dataSource == null) throw new IllegalStateException("No DataSource available");
                String jdbcUrl = getJdbcUrlFromDataSource(dataSource);
                if (jdbcUrl != null) {
                    return inferDbTypeFromUrl(jdbcUrl);
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }

    /**
     * 从不同类型的数据源中获取JDBC URL（统一反射策略，避免重复分支）
     */
    private String getJdbcUrlFromDataSource(javax.sql.DataSource dataSource) {
        // 依次尝试常见的 getter 方法和字段名
        String[] methodNames = {"getJdbcUrl", "getUrl"};
        String[] fieldNames = {"jdbcUrl", "url"};

        for (String methodName : methodNames) {
            try {
                Method method = dataSource.getClass().getMethod(methodName);
                String result = (String) method.invoke(dataSource);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }

        for (String fieldName : fieldNames) {
            try {
                Field field = dataSource.getClass().getDeclaredField(fieldName);
                field.setAccessible(true);
                String result = (String) field.get(dataSource);
                if (result != null) return result;
            } catch (Exception ignored) {}
        }

        log.debug("无法从 {} 获取JDBC URL", dataSource.getClass().getName());
        return null;
    }

    /**
     * 从JDBC URL推断数据库类型
     */
    private String inferDbTypeFromUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            return "unknown";
        }

        Matcher matcher = DB_TYPE_PATTERN.matcher(jdbcUrl);
        if (matcher.matches()) {
            return matcher.group(1).toLowerCase();
        }

        jdbcUrl = jdbcUrl.toLowerCase();
        if (jdbcUrl.contains("mysql")) return "mysql";
        if (jdbcUrl.contains("oracle")) return "oracle";
        if (jdbcUrl.contains(":dm:")) return "dm";
        if (jdbcUrl.contains("postgresql")) return "postgresql";
        if (jdbcUrl.contains("sqlserver")) return "sqlserver";

        return "unknown";
    }

    /**
     * 数据库类型缓存
     */
    private static class DatabaseTypeCache {
        final String dbType;
        final long fetchTime;

        DatabaseTypeCache(String dbType, long fetchTime) {
            this.dbType = dbType;
            this.fetchTime = fetchTime;
        }
    }
}
