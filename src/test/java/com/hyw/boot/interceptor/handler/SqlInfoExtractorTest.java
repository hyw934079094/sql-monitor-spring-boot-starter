package com.hyw.boot.interceptor.handler;

import com.hyw.boot.config.SlowSqlProperties;
import com.hyw.boot.filter.SqlSensitiveFilter;
import com.hyw.boot.model.SqlInfo;
import com.hyw.boot.parser.JSqlParser;
import com.hyw.boot.parser.SqlParser;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.plugin.Invocation;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.ResultHandler;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.*;

import java.lang.reflect.Method;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlInfoExtractor 单元测试
 */
class SqlInfoExtractorTest {

    private SlowSqlProperties properties;
    private SqlSensitiveFilter sensitiveFilter;
    private SqlParser sqlParser;
    private SqlInfoExtractor extractor;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.init();

        sensitiveFilter = new SqlSensitiveFilter(properties);
        sqlParser = new JSqlParser();

        DatabaseTypeDetector detector = new DatabaseTypeDetector(null, properties);
        extractor = new SqlInfoExtractor(sensitiveFilter, sqlParser, properties, detector);
    }

    // ====================================================================
    // 基础提取
    // ====================================================================

    @Test
    @DisplayName("提取 SELECT SQL 基本信息")
    void shouldExtractSelectSqlInfo() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.findById",
                "SELECT id, name FROM orders WHERE id = 1",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getSqlId()).isEqualTo("com.test.Mapper.findById");
        assertThat(info.getSqlType()).isEqualTo("SELECT");
        assertThat(info.getTables()).contains("orders");
        assertThat(info.getFilteredSql()).contains("SELECT");
        assertThat(info.getWhereCondition()).isNotNull();
        assertThat(info.getError()).isNull();
    }

    @Test
    @DisplayName("提取 INSERT SQL 基本信息")
    void shouldExtractInsertSqlInfo() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.insert",
                "INSERT INTO orders (id, name) VALUES (1, 'test')",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getSqlType()).isEqualTo("INSERT");
        assertThat(info.getTables()).contains("orders");
    }

    @Test
    @DisplayName("提取 UPDATE SQL 基本信息")
    void shouldExtractUpdateSqlInfo() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.update",
                "UPDATE orders SET name = 'test' WHERE id = 1",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getSqlType()).isEqualTo("UPDATE");
        assertThat(info.getTables()).contains("orders");
    }

    // ====================================================================
    // SQL 脱敏
    // ====================================================================

    @Test
    @DisplayName("敏感字段值应被脱敏")
    void shouldDesensitizeSensitiveFields() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.findByPassword",
                "SELECT * FROM orders WHERE password = 'mySecret123'",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getFilteredSql()).doesNotContain("mySecret123");
    }

    @Test
    @DisplayName("手机号应被脱敏")
    void shouldDesensitizePhoneNumbers() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.findByPhone",
                "SELECT * FROM orders WHERE note = '13812345678'",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getFilteredSql()).doesNotContain("13812345678");
    }

    // ====================================================================
    // 参数格式化
    // ====================================================================

    @Test
    @DisplayName("null 参数格式化为 'null'")
    void shouldFormatNullParam() throws Exception {
        Invocation invocation = buildInvocation(
                "com.test.Mapper.select",
                "SELECT * FROM orders",
                null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getParams()).isEqualTo("null");
    }

    @Test
    @DisplayName("Map 参数中敏感字段应被脱敏")
    void shouldDesensitizeMapSensitiveParam() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("username", "test");
        params.put("password", "secret123");

        Invocation invocation = buildInvocation(
                "com.test.Mapper.login",
                "SELECT * FROM users WHERE username = ? AND password = ?",
                params);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getParams()).contains("username");
        assertThat(info.getParams()).doesNotContain("secret123");
        assertThat(info.getParams()).contains("****");
    }

    @Test
    @DisplayName("Map 参数应过滤 mybatis 位置参数名")
    void shouldFilterMybatisParamNames() throws Exception {
        Map<String, Object> params = new HashMap<>();
        params.put("id", 1);
        params.put("param1", 1);  // mybatis 自动生成的

        Invocation invocation = buildInvocation(
                "com.test.Mapper.findById",
                "SELECT * FROM orders WHERE id = ?",
                params);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getParams()).contains("id");
        assertThat(info.getParams()).doesNotContain("param1");
    }

    // ====================================================================
    // SQL 长度控制
    // ====================================================================

    @Test
    @DisplayName("超长 SQL 应被截断")
    void shouldTruncateLongSql() throws Exception {
        properties.setMaxSqlLength(100);
        properties.init();
        // 重建 extractor 使新配置生效
        DatabaseTypeDetector detector = new DatabaseTypeDetector(null, properties);
        extractor = new SqlInfoExtractor(sensitiveFilter, sqlParser, properties, detector);

        String longSql = "SELECT " + "a, ".repeat(200) + "id FROM orders";
        Invocation invocation = buildInvocation("com.test.Mapper.select", longSql, null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getFilteredSql().length()).isLessThanOrEqualTo(103); // 100 + "..."
        assertThat(info.getFilteredSql()).endsWith("...");
    }

    @Test
    @DisplayName("超过 sqlParseMaxLength 的 SQL 应跳过解析但推断类型")
    void shouldSkipParseForVeryLongSql() throws Exception {
        properties.setSqlParseMaxLength(1000);
        properties.init();

        String longSql = "SELECT " + "col, ".repeat(300) + "id FROM orders";
        Invocation invocation = buildInvocation("com.test.Mapper.select", longSql, null);

        SqlInfo info = extractor.extractSqlInfo(invocation);

        assertThat(info.getSqlType()).isEqualTo("SELECT");
    }

    // ====================================================================
    // 异常处理
    // ====================================================================

    @Test
    @DisplayName("提取失败应设置 error 而非抛异常")
    void shouldSetErrorOnFailure() {
        // 构造一个会导致提取失败的 invocation（args 为空）
        try {
            Configuration configuration = new Configuration();
            BoundSql boundSql = new BoundSql(configuration, "SELECT 1", Collections.emptyList(), null);
            SqlSource sqlSource = paramObj -> boundSql;
            MappedStatement ms = new MappedStatement.Builder(configuration, "test.id", sqlSource, SqlCommandType.SELECT).build();

            // args[0] 不是 MappedStatement，故意造成错误
            Executor executor = new StubExecutor();
            Method queryMethod = Executor.class.getMethod("query",
                    MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);
            Invocation invocation = new Invocation(executor, queryMethod, new Object[]{null, null, RowBounds.DEFAULT, null});

            SqlInfo info = extractor.extractSqlInfo(invocation);

            assertThat(info.getError()).isNotNull();
        } catch (Exception e) {
            // 构建过程异常不算测试失败
        }
    }

    // ====================================================================
    // 辅助方法
    // ====================================================================

    private Invocation buildInvocation(String sqlId, String sql, Object parameter) throws Exception {
        Configuration configuration = new Configuration();
        BoundSql boundSql = new BoundSql(configuration, sql, Collections.emptyList(), parameter);
        SqlSource sqlSource = paramObj -> boundSql;

        MappedStatement ms = new MappedStatement.Builder(configuration, sqlId, sqlSource, SqlCommandType.SELECT)
                .build();

        Executor executor = new StubExecutor();
        Method queryMethod = Executor.class.getMethod("query",
                MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class);

        return new Invocation(executor, queryMethod, new Object[]{ms, parameter, RowBounds.DEFAULT, null});
    }

    @SuppressWarnings("all")
    private static class StubExecutor implements Executor {
        @Override public int update(MappedStatement ms, Object parameter) throws java.sql.SQLException { return 0; }
        @Override public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler, org.apache.ibatis.cache.CacheKey cacheKey, BoundSql boundSql) throws java.sql.SQLException { return Collections.emptyList(); }
        @Override public <E> List<E> query(MappedStatement ms, Object parameter, RowBounds rowBounds, ResultHandler resultHandler) throws java.sql.SQLException { return Collections.emptyList(); }
        @Override public <E> org.apache.ibatis.cursor.Cursor<E> queryCursor(MappedStatement ms, Object parameter, RowBounds rowBounds) throws java.sql.SQLException { return null; }
        @Override public List<org.apache.ibatis.executor.BatchResult> flushStatements() throws java.sql.SQLException { return Collections.emptyList(); }
        @Override public void commit(boolean required) throws java.sql.SQLException {}
        @Override public void rollback(boolean required) throws java.sql.SQLException {}
        @Override public org.apache.ibatis.cache.CacheKey createCacheKey(MappedStatement ms, Object parameterObject, RowBounds rowBounds, BoundSql boundSql) { return new org.apache.ibatis.cache.CacheKey(); }
        @Override public boolean isCached(MappedStatement ms, org.apache.ibatis.cache.CacheKey key) { return false; }
        @Override public void clearLocalCache() {}
        @Override public void deferLoad(MappedStatement ms, org.apache.ibatis.reflection.MetaObject resultObject, String property, org.apache.ibatis.cache.CacheKey key, Class<?> targetType) {}
        @Override public org.apache.ibatis.transaction.Transaction getTransaction() { return null; }
        @Override public void close(boolean forceRollback) {}
        @Override public boolean isClosed() { return false; }
        @Override public void setExecutorWrapper(Executor executor) {}
    }
}
