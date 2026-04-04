package com.hyw.boot.autoconfigure;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.decorator.EnhancedMdcTaskDecorator;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.handler.SlowSqlHandler;
import com.hyw.boot.interceptor.UniversalSlowSqlInterceptor;
import com.hyw.boot.monitor.MdcMonitor;
import com.hyw.boot.monitor.MdcMonitorScheduler;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 慢SQL监控自动配置类
 *
 * @author hyw
 * @version 3.0.0
 */
@AutoConfiguration
@ConditionalOnClass({Executor.class, MappedStatement.class})
@ConditionalOnProperty(prefix = "hyw.sql.monitor", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(SlowSqlProperties.class)
@AutoConfigureAfter(name = {
        "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration",
        "com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration",
        "org.mybatis.spring.boot.autoconfigure.MybatisAutoConfiguration"
})
public class SlowSqlAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(SlowSqlAutoConfiguration.class);

    @Bean
    @ConditionalOnMissingBean
    public SqlSensitiveFilter sqlSensitiveFilter(SlowSqlProperties properties) {
        return new SqlSensitiveFilter(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlParser sqlParser() {
        return new JSqlParser();
    }

    @Bean(name = "sqlMonitorMdcTaskDecorator")
    @ConditionalOnMissingBean(name = "sqlMonitorMdcTaskDecorator")
    public TaskDecorator sqlMonitorMdcTaskDecorator(SlowSqlProperties properties) {
        return new EnhancedMdcTaskDecorator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcMonitor mdcMonitor(SlowSqlProperties properties) {
        return new MdcMonitor(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "hyw.sql.monitor.mdc", name = "enabled", havingValue = "true", matchIfMissing = true)
    public MdcMonitorScheduler mdcMonitorScheduler(MdcMonitor mdcMonitor, SlowSqlProperties properties) {
        return new MdcMonitorScheduler(mdcMonitor, properties);
    }

    @Bean(name = "sqlMonitorExecutor")
    @ConditionalOnMissingBean(name = "sqlMonitorExecutor")
    public ThreadPoolTaskExecutor sqlMonitorExecutor(
            SlowSqlProperties properties,
            @Qualifier("sqlMonitorMdcTaskDecorator") TaskDecorator mdcTaskDecorator) {

        SlowSqlProperties.PoolConfig poolConfig = properties.getPool();

        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(poolConfig.getCoreSize());
        executor.setMaxPoolSize(poolConfig.getMaxSize());
        executor.setQueueCapacity(poolConfig.getQueueCapacity());
        executor.setKeepAliveSeconds(poolConfig.getKeepAliveSeconds());
        executor.setThreadNamePrefix("sql-monitor-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(5);
        executor.setTaskDecorator(mdcTaskDecorator);
        // AbortPolicy: 队列满时抛出 TaskRejectedException，由拦截器捕获并丢弃，避免阻塞业务线程
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        // 监控场景下保留核心线程，避免频繁创建/销毁线程的开销

        log.debug("初始化SQL监控线程池 - core={}, max={}, queue={}, MDC keys={}",
                poolConfig.getCoreSize(), poolConfig.getMaxSize(),
                poolConfig.getQueueCapacity(), properties.getMdc().getMdcKeys());

        // 不手动调用 initialize()，Spring 会通过 InitializingBean.afterPropertiesSet() 自动初始化
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    public UniversalSlowSqlInterceptor slowSqlInterceptor(
            SlowSqlProperties properties,
            SqlSensitiveFilter sensitiveFilter,
            SqlParser sqlParser,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            ApplicationContext applicationContext,
            @Qualifier("sqlMonitorExecutor") ThreadPoolTaskExecutor asyncExecutor,
            ObjectProvider<MdcMonitor> mdcMonitor,
            ObjectProvider<SlowSqlHandler> slowSqlHandlers) {

        return new UniversalSlowSqlInterceptor(
                properties,
                sensitiveFilter,
                sqlParser,
                meterRegistry.getIfAvailable(),
                applicationContext,
                asyncExecutor,
                mdcMonitor.getIfAvailable(),
                slowSqlHandlers.orderedStream().toList()
        );
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.boot.actuate.health.HealthIndicator")
    public SqlMonitorHealthIndicator sqlMonitorHealthIndicator(
            SlowSqlProperties properties,
            UniversalSlowSqlInterceptor interceptor) {
        return new SqlMonitorHealthIndicator(properties, interceptor);
    }
}
