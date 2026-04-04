package com.hyw.boot.autoconfigure;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.interceptor.UniversalSlowSqlInterceptor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * SQL 监控健康检查指示器。
 *
 * <p>通过 {@code /actuator/health} 暴露以下状态：</p>
 * <ul>
 *   <li><b>UP</b> — 监控正常运行</li>
 *   <li><b>DOWN</b> — 熔断器已触发（降级模式）</li>
 *   <li><b>DOWN</b> — 监控已禁用</li>
 * </ul>
 *
 * <p>仅在消费方引入 {@code spring-boot-starter-actuator} 后自动激活。</p>
 *
 * @author hyw
 * @version 3.0.0
 */
public class SqlMonitorHealthIndicator implements HealthIndicator {

    private final SlowSqlProperties properties;
    private final UniversalSlowSqlInterceptor interceptor;

    public SqlMonitorHealthIndicator(SlowSqlProperties properties,
                                     UniversalSlowSqlInterceptor interceptor) {
        this.properties = properties;
        this.interceptor = interceptor;
    }

    @Override
    public Health health() {
        if (!properties.isEnabled()) {
            return Health.down()
                    .withDetail("status", "disabled")
                    .build();
        }

        Health.Builder builder;
        boolean circuitOpen = interceptor.isCircuitBreakerOpen();

        if (circuitOpen) {
            builder = Health.down()
                    .withDetail("circuitBreaker", "OPEN");
        } else {
            builder = Health.up()
                    .withDetail("circuitBreaker", "CLOSED");
        }

        builder.withDetail("consecutiveFailures", interceptor.getConsecutiveFailures());

        // 线程池状态
        ThreadPoolTaskExecutor executor = interceptor.getAsyncExecutor();
        if (executor != null && executor.getThreadPoolExecutor() != null) {
            var pool = executor.getThreadPoolExecutor();
            builder.withDetail("threadPool.activeCount", pool.getActiveCount())
                    .withDetail("threadPool.poolSize", pool.getPoolSize())
                    .withDetail("threadPool.queueSize", pool.getQueue().size())
                    .withDetail("threadPool.queueCapacity", pool.getQueue().remainingCapacity() + pool.getQueue().size())
                    .withDetail("threadPool.completedTasks", pool.getCompletedTaskCount());
        }

        builder.withDetail("slowThreshold", properties.getSlowThreshold() + "ms")
                .withDetail("sampleRate", properties.getSampleRate());

        return builder.build();
    }
}
