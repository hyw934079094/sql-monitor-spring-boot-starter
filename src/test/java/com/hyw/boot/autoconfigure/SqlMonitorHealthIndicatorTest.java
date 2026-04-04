package com.hyw.boot.autoconfigure;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.interceptor.UniversalSlowSqlInterceptor;
import com.hyw.boot.parser.JSqlParser;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlMonitorHealthIndicator 单元测试
 */
class SqlMonitorHealthIndicatorTest {

    private SlowSqlProperties properties;
    private UniversalSlowSqlInterceptor interceptor;
    private SqlMonitorHealthIndicator healthIndicator;
    private ThreadPoolTaskExecutor asyncExecutor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.init();

        asyncExecutor = new ThreadPoolTaskExecutor();
        asyncExecutor.setCorePoolSize(2);
        asyncExecutor.setMaxPoolSize(4);
        asyncExecutor.setQueueCapacity(100);
        asyncExecutor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        asyncExecutor.initialize();

        interceptor = new UniversalSlowSqlInterceptor(
                properties, new SqlSensitiveFilter(properties), new JSqlParser(),
                new SimpleMeterRegistry(), null, asyncExecutor, null, Collections.emptyList()
        );
        interceptor.init();

        healthIndicator = new SqlMonitorHealthIndicator(properties, interceptor);
    }

    @AfterEach
    void tearDown() {
        interceptor.destroy();
        asyncExecutor.shutdown();
    }

    @Test
    @DisplayName("正常状态应返回 UP")
    void shouldReturnUpWhenHealthy() {
        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails().get("circuitBreaker")).isEqualTo("CLOSED");
        assertThat(health.getDetails().get("consecutiveFailures")).isEqualTo(0);
    }

    @Test
    @DisplayName("禁用时应返回 DOWN + disabled")
    void shouldReturnDownWhenDisabled() {
        properties.setEnabled(false);

        Health health = healthIndicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails().get("status")).isEqualTo("disabled");
    }

    @Test
    @DisplayName("应包含线程池详情")
    void shouldIncludeThreadPoolDetails() {
        Health health = healthIndicator.health();

        assertThat(health.getDetails()).containsKey("threadPool.activeCount");
        assertThat(health.getDetails()).containsKey("threadPool.poolSize");
        assertThat(health.getDetails()).containsKey("threadPool.queueSize");
        assertThat(health.getDetails()).containsKey("threadPool.queueCapacity");
        assertThat(health.getDetails()).containsKey("threadPool.completedTasks");
    }

    @Test
    @DisplayName("应包含配置信息")
    void shouldIncludeConfigDetails() {
        Health health = healthIndicator.health();

        assertThat(health.getDetails().get("slowThreshold")).isEqualTo("1000ms");
        assertThat(health.getDetails().get("sampleRate")).isEqualTo(0.01);
    }
}
