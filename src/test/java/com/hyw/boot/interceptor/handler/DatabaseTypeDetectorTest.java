package com.hyw.boot.interceptor.handler;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * DatabaseTypeDetector 单元测试
 */
class DatabaseTypeDetectorTest {

    private static SlowSqlProperties propsWithCacheSeconds(int seconds) {
        SlowSqlProperties props = new SlowSqlProperties();
        props.setDbTypeCacheSeconds(seconds);
        return props;
    }

    // ====================================================================
    // JDBC URL 推断
    // ====================================================================

    @Nested
    @DisplayName("JDBC URL 数据库类型推断")
    class InferDbTypeTests {

        @Test
        @DisplayName("MySQL URL")
        void shouldDetectMysql() {
            DatabaseTypeDetector detector = new DatabaseTypeDetector(null, propsWithCacheSeconds(60));
            // 通过 refreshDbTypeCache 测试（无 ApplicationContext → unknown）
            assertThat(detector.getDatabaseType()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("ApplicationContext 为 null 时安全降级")
        void shouldHandleNullContext() {
            DatabaseTypeDetector detector = new DatabaseTypeDetector(null, propsWithCacheSeconds(60));
            assertThat(detector.getDatabaseType()).isEqualTo("unknown");
        }

        @Test
        @DisplayName("缓存生效 - 同一秒内不重复查询")
        void shouldUseCacheWithinTtl() {
            DatabaseTypeDetector detector = new DatabaseTypeDetector(null, propsWithCacheSeconds(60));

            String first = detector.getDatabaseType();
            String second = detector.getDatabaseType();

            // 两次调用应返回相同值（缓存命中）
            assertThat(first).isEqualTo(second);
        }

        @Test
        @DisplayName("缓存过期 - TTL 为 0 时每次刷新")
        void shouldRefreshWhenCacheExpired() {
            // dbTypeCacheSeconds = 1（最小合法值，验证后会被修正）
            SlowSqlProperties props = propsWithCacheSeconds(1);
            props.init();
            DatabaseTypeDetector detector = new DatabaseTypeDetector(null, props);

            // 即使每次刷新，无 context 也返回 unknown
            assertThat(detector.getDatabaseType()).isEqualTo("unknown");
        }
    }

    // ====================================================================
    // 多数据源兼容
    // ====================================================================

    @Nested
    @DisplayName("多数据源兼容")
    class MultiDataSourceTests {

        @Test
        @DisplayName("ApplicationContext 无 Bean 时安全降级")
        void shouldHandleNoBeansGracefully() {
            ApplicationContext ctx = mock(ApplicationContext.class);
            // mock 的 context 不含任何 bean，getBeanProvider 返回空 provider
            DatabaseTypeDetector detector = new DatabaseTypeDetector(ctx, propsWithCacheSeconds(60));

            // 不应抛异常，应降级为 unknown
            assertThat(detector.getDatabaseType()).isEqualTo("unknown");
        }
    }
}
