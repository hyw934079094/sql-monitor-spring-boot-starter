package com.hyw.boot.autoconfigure;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.decorator.EnhancedMdcTaskDecorator;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.interceptor.UniversalSlowSqlInterceptor;
import com.hyw.boot.monitor.MdcMonitor;
import com.hyw.boot.monitor.MdcMonitorScheduler;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.MappedStatement;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 慢SQL监控自动配置类
 *
 * @author hyw
 * @version 3.0.0
 */
@AutoConfiguration
@EnableScheduling
@ConditionalOnClass({Executor.class, MappedStatement.class})
@EnableConfigurationProperties(SlowSqlProperties.class)
@AutoConfigureAfter(DataSourceAutoConfiguration.class)
@Import({MdcMonitorScheduler.class})
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

    @Bean
    @ConditionalOnMissingBean
    public TaskDecorator mdcTaskDecorator(SlowSqlProperties properties) {
        return new EnhancedMdcTaskDecorator(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public MdcMonitor mdcMonitor(SlowSqlProperties properties) {
        return new MdcMonitor(properties);
    }

    @Bean(name = "sqlMonitorExecutor")
    @ConditionalOnMissingBean(name = "sqlMonitorExecutor")
    public ThreadPoolTaskExecutor sqlMonitorExecutor(
            SlowSqlProperties properties,
            TaskDecorator mdcTaskDecorator) {

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
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setAllowCoreThreadTimeOut(true);

        log.info("初始化SQL监控线程池 (Spring Boot 3) - core={}, max={}, queue={}, MDC keys={}",
                poolConfig.getCoreSize(), poolConfig.getMaxSize(),
                poolConfig.getQueueCapacity(), properties.getMdc().getMdcKeys());

        executor.initialize();
        return executor;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SqlSessionFactory.class)
    public UniversalSlowSqlInterceptor slowSqlInterceptor(
            SlowSqlProperties properties,
            SqlSensitiveFilter sensitiveFilter,
            SqlParser sqlParser,
            ObjectProvider<io.micrometer.core.instrument.MeterRegistry> meterRegistry,
            ObjectProvider<ApplicationContext> applicationContext,
            @Qualifier("sqlMonitorExecutor") ThreadPoolTaskExecutor asyncExecutor,
            ObjectProvider<MdcMonitor> mdcMonitor) {

        return new UniversalSlowSqlInterceptor(
                properties,
                sensitiveFilter,
                sqlParser,
                meterRegistry.getIfAvailable(),
                applicationContext.getIfAvailable(),
                asyncExecutor,
                mdcMonitor.getIfAvailable()
        );
    }
}