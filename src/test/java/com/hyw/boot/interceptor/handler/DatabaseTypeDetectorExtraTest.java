package com.hyw.boot.interceptor.handler;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.*;

import javax.sql.DataSource;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DatabaseTypeDetector 额外覆盖测试：覆盖反射获取 URL 与 URL 推断分支
 */
class DatabaseTypeDetectorExtraTest {

    private DatabaseTypeDetector detector;

    @BeforeEach
    void setUp() {
        SlowSqlProperties props = new SlowSqlProperties();
        props.init();
        detector = new DatabaseTypeDetector(null, props);
    }

    @Test
    @DisplayName("inferDbTypeFromUrl 应支持 pattern 与 contains 分支")
    void shouldInferDbTypeFromUrlBranches() throws Exception {
        Method infer = DatabaseTypeDetector.class.getDeclaredMethod("inferDbTypeFromUrl", String.class);
        infer.setAccessible(true);

        assertThat(infer.invoke(detector, "jdbc:mysql://localhost:3306/test"))
                .isEqualTo("mysql");
        assertThat(infer.invoke(detector, "jdbc:oracle:thin:@127.0.0.1:1521/ORCL"))
                .isEqualTo("oracle");
        assertThat(infer.invoke(detector, "jdbc:dm://127.0.0.1:5236/test"))
                .isEqualTo("dm");
        assertThat(infer.invoke(detector, "jdbc:postgresql://localhost:5432/test"))
                .isEqualTo("postgresql");
        assertThat(infer.invoke(detector, "jdbc:sqlserver://localhost:1433;databaseName=test"))
                .isEqualTo("sqlserver");
        assertThat(infer.invoke(detector, ""))
                .isEqualTo("unknown");
        assertThat(infer.invoke(detector, (Object) null))
                .isEqualTo("unknown");
    }

    @Test
    @DisplayName("getJdbcUrlFromDataSource 应覆盖 getUrl/getJdbcUrl 与字段读取分支")
    void shouldGetJdbcUrlFromDataSourceBranches() throws Exception {
        Method getUrl = DatabaseTypeDetector.class.getDeclaredMethod("getJdbcUrlFromDataSource", DataSource.class);
        getUrl.setAccessible(true);

        class DsWithGetUrl implements DataSource {
            public String getUrl() { return "jdbc:mysql://localhost:3306/test"; }
            @Override public java.sql.Connection getConnection() { return null; }
            @Override public java.sql.Connection getConnection(String username, String password) { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
        }

        class DsWithField implements DataSource {
            @SuppressWarnings("unused")
            private final String jdbcUrl = "jdbc:postgresql://localhost:5432/test";
            @Override public java.sql.Connection getConnection() { return null; }
            @Override public java.sql.Connection getConnection(String username, String password) { return null; }
            @Override public <T> T unwrap(Class<T> iface) { return null; }
            @Override public boolean isWrapperFor(Class<?> iface) { return false; }
            @Override public java.io.PrintWriter getLogWriter() { return null; }
            @Override public void setLogWriter(java.io.PrintWriter out) {}
            @Override public void setLoginTimeout(int seconds) {}
            @Override public int getLoginTimeout() { return 0; }
            @Override public java.util.logging.Logger getParentLogger() { return null; }
        }

        Object url1 = getUrl.invoke(detector, new DsWithGetUrl());
        assertThat(url1).isEqualTo("jdbc:mysql://localhost:3306/test");

        Object url2 = getUrl.invoke(detector, new DsWithField());
        assertThat(url2).isEqualTo("jdbc:postgresql://localhost:5432/test");
    }
}
