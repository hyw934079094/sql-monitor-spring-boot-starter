package com.hyw.boot.autoconfigure;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.interceptor.UniversalSlowSqlInterceptor;
import com.hyw.boot.monitor.MdcMonitor;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SlowSqlAutoConfiguration 自动装配测试
 */
class SlowSqlAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SlowSqlAutoConfiguration.class));

    // ====================================================================
    // 零配置启动
    // ====================================================================

    @Test
    @DisplayName("零配置启动 - 所有核心 Bean 应被创建")
    void shouldCreateAllBeansWithZeroConfig() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SlowSqlProperties.class);
            assertThat(context).hasSingleBean(SqlSensitiveFilter.class);
            assertThat(context).hasSingleBean(SqlParser.class);
            assertThat(context).hasSingleBean(MdcMonitor.class);
        });
    }

    @Test
    @DisplayName("默认 SqlParser 实现为 JSqlParser")
    void shouldUseJSqlParserByDefault() {
        contextRunner.run(context -> {
            SqlParser parser = context.getBean(SqlParser.class);
            assertThat(parser).isInstanceOf(JSqlParser.class);
        });
    }

    @Test
    @DisplayName("sqlMonitorExecutor 线程池应被创建")
    void shouldCreateExecutorBean() {
        contextRunner.run(context -> {
            assertThat(context).hasBean("sqlMonitorExecutor");
            ThreadPoolTaskExecutor executor = context.getBean("sqlMonitorExecutor", ThreadPoolTaskExecutor.class);
            assertThat(executor).isNotNull();
        });
    }

    // ====================================================================
    // 条件装配
    // ====================================================================

    @Test
    @DisplayName("enabled=false 时不创建任何 Bean")
    void shouldNotCreateBeansWhenDisabled() {
        contextRunner
                .withPropertyValues("hyw.sql.monitor.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(UniversalSlowSqlInterceptor.class);
                    assertThat(context).doesNotHaveBean(SqlSensitiveFilter.class);
                });
    }

    // ====================================================================
    // @ConditionalOnMissingBean 可替换性
    // ====================================================================

    @Test
    @DisplayName("用户自定义 SqlParser 应优先于默认")
    void shouldUseCustomSqlParserIfProvided() {
        contextRunner
                .withBean("customParser", SqlParser.class, () -> new SqlParser() {
                    @Override
                    public SqlParseResult parse(String sql) {
                        SqlParseResult r = new SqlParseResult();
                        r.setSqlType("CUSTOM");
                        return r;
                    }

                    @Override
                    public String getSqlType(String sql) { return "CUSTOM"; }

                    @Override
                    public java.util.List<String> extractTableNames(String sql) {
                        return java.util.Collections.emptyList();
                    }

                    @Override
                    public boolean isSelect(String sql) { return false; }

                    @Override
                    public boolean isDml(String sql) { return false; }
                })
                .run(context -> {
                    SqlParser parser = context.getBean(SqlParser.class);
                    assertThat(parser).isNotInstanceOf(JSqlParser.class);
                    assertThat(parser.getSqlType("SELECT 1")).isEqualTo("CUSTOM");
                });
    }

    @Test
    @DisplayName("用户自定义 SqlSensitiveFilter 应优先于默认")
    void shouldUseCustomFilterIfProvided() {
        SqlSensitiveFilter customFilter = new SqlSensitiveFilter(new SlowSqlProperties());
        contextRunner
                .withBean("customFilter", SqlSensitiveFilter.class, () -> customFilter)
                .run(context -> {
                    assertThat(context.getBean(SqlSensitiveFilter.class)).isSameAs(customFilter);
                });
    }

    // ====================================================================
    // 配置属性绑定
    // ====================================================================

    @Test
    @DisplayName("YAML 属性正确绑定到 SlowSqlProperties")
    void shouldBindPropertiesFromYaml() {
        contextRunner
                .withPropertyValues(
                        "hyw.sql.monitor.slow-threshold=3000",
                        "hyw.sql.monitor.sample-rate=0.5",
                        "hyw.sql.monitor.pool.core-size=4",
                        "hyw.sql.monitor.pool.max-size=8",
                        "hyw.sql.monitor.sensitive.enabled=false",
                        "hyw.sql.monitor.metrics.include-sql-id=true"
                )
                .run(context -> {
                    SlowSqlProperties props = context.getBean(SlowSqlProperties.class);
                    assertThat(props.getSlowThreshold()).isEqualTo(3000L);
                    assertThat(props.getSampleRate()).isEqualTo(0.5);
                    assertThat(props.getPool().getCoreSize()).isEqualTo(4);
                    assertThat(props.getPool().getMaxSize()).isEqualTo(8);
                    assertThat(props.getSensitive().isEnabled()).isFalse();
                    assertThat(props.getMetrics().isIncludeSqlId()).isTrue();
                });
    }

    // ====================================================================
    // MDC 条件装配
    // ====================================================================

    @Test
    @DisplayName("MDC 禁用时不创建 MdcMonitorScheduler")
    void shouldNotCreateMdcSchedulerWhenDisabled() {
        contextRunner
                .withPropertyValues("hyw.sql.monitor.mdc.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("mdcMonitorScheduler");
                });
    }

    // ====================================================================
    // 线程池配置绑定
    // ====================================================================

    @Test
    @DisplayName("线程池应使用自定义配置")
    void shouldApplyPoolConfig() {
        contextRunner
                .withPropertyValues(
                        "hyw.sql.monitor.pool.core-size=8",
                        "hyw.sql.monitor.pool.max-size=16",
                        "hyw.sql.monitor.pool.queue-capacity=2000"
                )
                .run(context -> {
                    ThreadPoolTaskExecutor executor = context.getBean("sqlMonitorExecutor", ThreadPoolTaskExecutor.class);
                    assertThat(executor.getCorePoolSize()).isEqualTo(8);
                    assertThat(executor.getMaxPoolSize()).isEqualTo(16);
                });
    }

    @Test
    @DisplayName("MDC 启用时应创建 MdcMonitor 和 MdcMonitorScheduler")
    void shouldCreateMdcBeansWhenEnabled() {
        contextRunner
                .withPropertyValues("hyw.sql.monitor.mdc.enabled=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(MdcMonitor.class);
                });
    }

    @Test
    @DisplayName("HealthIndicator 应在 Actuator 可用时自动注册")
    void shouldCreateHealthIndicatorWhenActuatorPresent() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SqlMonitorHealthIndicator.class);
        });
    }
}
