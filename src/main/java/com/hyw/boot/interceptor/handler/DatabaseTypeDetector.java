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
                org.apache.ibatis.session.SqlSessionFactory sqlSessionFactory =
                        applicationContext.getBean(org.apache.ibatis.session.SqlSessionFactory.class);
                String dbType = sqlSessionFactory.getConfiguration().getDatabaseId();
                if (dbType != null) return dbType;
            } catch (Exception ignored) {}

            try {
                javax.sql.DataSource dataSource = applicationContext.getBean(javax.sql.DataSource.class);
                String jdbcUrl = getJdbcUrlFromDataSource(dataSource);
                if (jdbcUrl != null) {
                    return inferDbTypeFromUrl(jdbcUrl);
                }
            } catch (Exception ignored) {}
        }
        return "unknown";
    }

    /**
     * 从不同类型的数据源中获取JDBC URL
     */
    private String getJdbcUrlFromDataSource(javax.sql.DataSource dataSource) {
        try {
            // 支持HikariDataSource（使用反射避免依赖）
            if (dataSource.getClass().getName().equals("com.zaxxer.hikari.HikariDataSource")) {
                Method getJdbcUrlMethod = dataSource.getClass().getMethod("getJdbcUrl");
                return (String) getJdbcUrlMethod.invoke(dataSource);
            }
            // 支持Tomcat JDBC DataSource
            else if (dataSource.getClass().getName().equals("org.apache.tomcat.jdbc.pool.DataSource")) {
                Method method = dataSource.getClass().getMethod("getUrl");
                return (String) method.invoke(dataSource);
            }
            // 支持DBCP2 DataSource（使用反射避免依赖）
            else if (dataSource.getClass().getName().equals("org.apache.commons.dbcp2.BasicDataSource")) {
                Method getUrlMethod = dataSource.getClass().getMethod("getUrl");
                return (String) getUrlMethod.invoke(dataSource);
            }
            // 支持其他数据源类型（通过反射）
            else {
                // 尝试通过反射获取url或jdbcUrl属性
                try {
                    // 尝试获取getUrl方法
                    Method getUrlMethod = dataSource.getClass().getMethod("getUrl");
                    return (String) getUrlMethod.invoke(dataSource);
                } catch (Exception e) {
                    // 尝试获取getJdbcUrl方法
                    try {
                        Method getJdbcUrlMethod = dataSource.getClass().getMethod("getJdbcUrl");
                        return (String) getJdbcUrlMethod.invoke(dataSource);
                    } catch (Exception ex) {
                        // 尝试获取url属性
                        try {
                            Field urlField = dataSource.getClass().getDeclaredField("url");
                            urlField.setAccessible(true);
                            return (String) urlField.get(dataSource);
                        } catch (Exception exc) {
                            // 尝试获取jdbcUrl属性
                            try {
                                Field jdbcUrlField = dataSource.getClass().getDeclaredField("jdbcUrl");
                                jdbcUrlField.setAccessible(true);
                                return (String) jdbcUrlField.get(dataSource);
                            } catch (Exception exce) {
                                // 无法获取URL
                                return null;
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("获取数据源URL失败: {}", e.getMessage());
            return null;
        }
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
        String dbType;
        long fetchTime;

        DatabaseTypeCache(String dbType, long fetchTime) {
            this.dbType = dbType;
            this.fetchTime = fetchTime;
        }
    }
}
