package com.hyw.boot.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 慢SQL监控配置属性类
 *
 * @author hyw
 * @version 3.0.0
 */
@Data
@ConfigurationProperties(prefix = "hyw.sql.monitor")
public class SlowSqlProperties implements EnvironmentAware {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlProperties.class);

    private boolean enabled = true;
    private long slowThreshold = 1000;
    private long criticalThreshold = 5000;
    private boolean logEnabled = false;
    private double sampleRate = 0.01;
    private int maxSqlLength = 2000;
    private int maxCacheSize = 1000;
    private int dbTypeCacheSeconds = 60;

    private SensitiveConfig sensitive = new SensitiveConfig();
    private PoolConfig pool = new PoolConfig();
    private MdcConfig mdc = new MdcConfig();

    private final List<ConfigChangeListener> listeners = new CopyOnWriteArrayList<>();
    private Environment environment;

    @Data
    public static class SensitiveConfig {
        private boolean enabled = true;
        private String maskChar = "*";
        private int maskLength = 4;
        private List<String> sensitiveFields = new ArrayList<>(Arrays.asList(
                "password", "pwd", "secret", "token",
                "id_card", "idcard", "phone", "mobile",
                "email", "bank_card", "credit_card"
        ));
        private List<String> sensitiveTables = new ArrayList<>(Arrays.asList(
                "user", "account", "payment", "customer"
        ));
    }

    @Data
    public static class PoolConfig {
        private int coreSize = 2;
        private int maxSize = 4;
        private int queueCapacity = 1000;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class MdcConfig {
        private boolean enabled = true;
        private double monitorRate = 0.01;
        private double alertThreshold = 99.5;
        private List<String> mdcKeys = new ArrayList<>(Arrays.asList(
                "traceId", "spanId", "userId", "requestId", "clientIp"
        ));
    }

    @PostConstruct
    public void init() {
        validateConfigs();
    }

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public void refreshConfig() {
        if (environment == null) return;

        SlowSqlConfigSnapshot oldConfig = snapshot();

        this.enabled = environment.getProperty("hyw.sql.monitor.enabled", Boolean.class, true);
        this.slowThreshold = environment.getProperty("hyw.sql.monitor.slow-threshold", Long.class, 1000L);
        this.criticalThreshold = environment.getProperty("hyw.sql.monitor.critical-threshold", Long.class, 5000L);
        this.sampleRate = environment.getProperty("hyw.sql.monitor.sample-rate", Double.class, 0.01);
        this.logEnabled = environment.getProperty("hyw.sql.monitor.log-enabled", Boolean.class, false);
        this.maxSqlLength = environment.getProperty("hyw.sql.monitor.max-sql-length", Integer.class, 2000);
        this.maxCacheSize = environment.getProperty("hyw.sql.monitor.max-cache-size", Integer.class, 1000);

        // 刷新MDC配置
        MdcConfig mdcConfig = this.mdc;
        mdcConfig.setMonitorRate(environment.getProperty("hyw.sql.monitor.mdc.monitor-rate", Double.class, 0.01));
        mdcConfig.setAlertThreshold(environment.getProperty("hyw.sql.monitor.mdc.alert-threshold", Double.class, 99.5));

        validateConfigs();
        notifyListeners(oldConfig);
    }

    private void validateConfigs() {
        if (slowThreshold <= 0) slowThreshold = 1000;
        if (criticalThreshold < slowThreshold) criticalThreshold = slowThreshold * 2;
        sampleRate = Math.max(0, Math.min(1, sampleRate));
        if (maxSqlLength < 100) maxSqlLength = 100;
        if (maxCacheSize < 100) maxCacheSize = 100;
        if (dbTypeCacheSeconds < 1) dbTypeCacheSeconds = 1;

        PoolConfig poolConfig = this.pool;
        if (poolConfig.getCoreSize() < 1) poolConfig.setCoreSize(1);
        if (poolConfig.getMaxSize() < poolConfig.getCoreSize()) {
            poolConfig.setMaxSize(poolConfig.getCoreSize());
        }
        if (poolConfig.getQueueCapacity() < 100) poolConfig.setQueueCapacity(100);
        if (poolConfig.getKeepAliveSeconds() < 10) poolConfig.setKeepAliveSeconds(10);
    }

    public void mergeProperties(Properties props) {
        SlowSqlConfigSnapshot oldConfig = snapshot();

        if (props.containsKey("enabled")) {
            this.enabled = Boolean.parseBoolean(props.getProperty("enabled"));
        }
        if (props.containsKey("slowThreshold")) {
            this.slowThreshold = parseLong(props.getProperty("slowThreshold"), 1000L);
        }
        if (props.containsKey("criticalThreshold")) {
            this.criticalThreshold = parseLong(props.getProperty("criticalThreshold"), 5000L);
        }
        if (props.containsKey("sampleRate")) {
            this.sampleRate = parseDouble(props.getProperty("sampleRate"), 0.01);
        }
        if (props.containsKey("logEnabled")) {
            this.logEnabled = Boolean.parseBoolean(props.getProperty("logEnabled"));
        }
        if (props.containsKey("maxCacheSize")) {
            this.maxCacheSize = parseInt(props.getProperty("maxCacheSize"), 1000);
        }

        validateConfigs();
        notifyListeners(oldConfig);
    }

    public void addListener(ConfigChangeListener listener) {
        listeners.add(listener);
    }

    private void notifyListeners(SlowSqlConfigSnapshot oldConfig) {
        SlowSqlConfigSnapshot newConfig = snapshot();
        if (oldConfig.equals(newConfig)) return;
        for (ConfigChangeListener listener : listeners) {
            try {
                listener.onConfigChange(oldConfig, newConfig);
            } catch (Exception e) {
                log.error("通知配置变更失败", e);
            }
        }
    }

    private SlowSqlConfigSnapshot snapshot() {
        return new SlowSqlConfigSnapshot(enabled, slowThreshold, criticalThreshold,
                logEnabled, sampleRate, maxSqlLength, maxCacheSize);
    }

    // 使用全限定名避免与 org.springframework.beans.factory.annotation.Value 冲突
    @lombok.Value
    public static class SlowSqlConfigSnapshot {
        boolean enabled;
        long slowThreshold;
        long criticalThreshold;
        boolean logEnabled;
        double sampleRate;
        int maxSqlLength;
        int maxCacheSize;
    }

    private long parseLong(String value, long defaultValue) {
        try { return Long.parseLong(value); }
        catch (Exception e) { return defaultValue; }
    }

    private double parseDouble(String value, double defaultValue) {
        try { return Double.parseDouble(value); }
        catch (Exception e) { return defaultValue; }
    }

    private int parseInt(String value, int defaultValue) {
        try { return Integer.parseInt(value); }
        catch (Exception e) { return defaultValue; }
    }

    public interface ConfigChangeListener {
        void onConfigChange(SlowSqlConfigSnapshot oldConfig, SlowSqlConfigSnapshot newConfig);
    }
}
