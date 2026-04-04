package com.hyw.boot.parser;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JSqlParser 单元测试
 */
class JSqlParserTest {

    private JSqlParser parser;

    @BeforeEach
    void setUp() {
        parser = new JSqlParser();
    }

    // ====================================================================
    // SQL 类型识别
    // ====================================================================

    @Nested
    @DisplayName("SQL 类型识别")
    class SqlTypeTests {

        @Test
        @DisplayName("SELECT 语句")
        void shouldDetectSelect() {
            assertThat(parser.getSqlType("SELECT id, name FROM user WHERE id = 1")).isEqualTo("SELECT");
        }

        @Test
        @DisplayName("INSERT 语句")
        void shouldDetectInsert() {
            assertThat(parser.getSqlType("INSERT INTO user (name) VALUES ('test')")).isEqualTo("INSERT");
        }

        @Test
        @DisplayName("UPDATE 语句")
        void shouldDetectUpdate() {
            assertThat(parser.getSqlType("UPDATE user SET name = 'test' WHERE id = 1")).isEqualTo("UPDATE");
        }

        @Test
        @DisplayName("DELETE 语句")
        void shouldDetectDelete() {
            assertThat(parser.getSqlType("DELETE FROM user WHERE id = 1")).isEqualTo("DELETE");
        }

        @Test
        @DisplayName("非法 SQL 返回 UNKNOWN")
        void shouldReturnUnknownForInvalidSql() {
            assertThat(parser.getSqlType("THIS IS NOT SQL")).isEqualTo("UNKNOWN");
        }
    }

    // ====================================================================
    // 表名提取
    // ====================================================================

    @Nested
    @DisplayName("表名提取")
    class TableExtractionTests {

        @Test
        @DisplayName("单表 SELECT")
        void shouldExtractSingleTable() {
            List<String> tables = parser.extractTableNames("SELECT * FROM user WHERE id = 1");
            assertThat(tables).containsExactly("user");
        }

        @Test
        @DisplayName("多表 JOIN")
        void shouldExtractJoinTables() {
            List<String> tables = parser.extractTableNames(
                    "SELECT u.name, o.amount FROM user u JOIN orders o ON u.id = o.user_id");
            assertThat(tables).containsExactlyInAnyOrder("user", "orders");
        }

        @Test
        @DisplayName("子查询中的表")
        void shouldExtractSubqueryTables() {
            List<String> tables = parser.extractTableNames(
                    "SELECT * FROM user WHERE id IN (SELECT user_id FROM orders)");
            assertThat(tables).containsExactlyInAnyOrder("user", "orders");
        }

        @Test
        @DisplayName("INSERT 表名")
        void shouldExtractInsertTable() {
            List<String> tables = parser.extractTableNames("INSERT INTO user (name) VALUES ('test')");
            assertThat(tables).contains("user");
        }

        @Test
        @DisplayName("非法 SQL 返回空列表")
        void shouldReturnEmptyForInvalidSql() {
            assertThat(parser.extractTableNames("INVALID SQL")).isEmpty();
        }
    }

    // ====================================================================
    // 完整解析
    // ====================================================================

    @Nested
    @DisplayName("完整解析")
    class FullParseTests {

        @Test
        @DisplayName("SELECT 解析 - WHERE / ORDER BY / LIMIT / JOIN / 子查询")
        void shouldParseSelectFully() {
            String sql = "SELECT u.id, u.name FROM user u " +
                    "JOIN orders o ON u.id = o.user_id " +
                    "WHERE u.status = 1 ORDER BY u.id LIMIT 10";
            SqlParser.SqlParseResult result = parser.parse(sql);

            assertThat(result.getSqlType()).isEqualTo("SELECT");
            assertThat(result.getTables()).containsExactlyInAnyOrder("user", "orders");
            assertThat(result.getWhereCondition()).isNotNull();
            assertThat(result.isHasJoin()).isTrue();
            assertThat(result.getOrderBy()).isNotNull();
            assertThat(result.getLimit()).isNotNull();
        }

        @Test
        @DisplayName("UPDATE 解析 - WHERE + 列名")
        void shouldParseUpdateFully() {
            SqlParser.SqlParseResult result = parser.parse(
                    "UPDATE user SET name = 'test', age = 18 WHERE id = 1");

            assertThat(result.getSqlType()).isEqualTo("UPDATE");
            assertThat(result.getTables()).contains("user");
            assertThat(result.getWhereCondition()).isNotNull();
            assertThat(result.getColumns()).containsExactly("name", "age");
        }

        @Test
        @DisplayName("INSERT 解析 - 列名提取")
        void shouldParseInsertColumns() {
            SqlParser.SqlParseResult result = parser.parse(
                    "INSERT INTO user (name, age, email) VALUES ('test', 18, 'test@test.com')");

            assertThat(result.getSqlType()).isEqualTo("INSERT");
            assertThat(result.getColumns()).containsExactly("name", "age", "email");
        }

        @Test
        @DisplayName("DELETE 解析 - WHERE 条件")
        void shouldParseDeleteWhere() {
            SqlParser.SqlParseResult result = parser.parse("DELETE FROM user WHERE id = 1 AND status = 0");

            assertThat(result.getSqlType()).isEqualTo("DELETE");
            assertThat(result.getWhereCondition()).contains("id");
        }

        @Test
        @DisplayName("子查询检测")
        void shouldDetectSubQuery() {
            SqlParser.SqlParseResult result = parser.parse(
                    "SELECT * FROM user WHERE id IN (SELECT user_id FROM orders)");
            assertThat(result.isHasSubQuery()).isTrue();
        }

        @Test
        @DisplayName("非法 SQL 解析不抛异常")
        void shouldNotThrowForInvalidSql() {
            SqlParser.SqlParseResult result = parser.parse("THIS IS NOT VALID SQL");
            assertThat(result.getSqlType()).isEqualTo("UNKNOWN");
        }
    }

    // ====================================================================
    // isSelect / isDml
    // ====================================================================

    @Nested
    @DisplayName("isSelect / isDml 判断")
    class TypeCheckTests {

        @Test
        void selectShouldBeSelect() {
            assertThat(parser.isSelect("SELECT 1")).isTrue();
            assertThat(parser.isSelect("INSERT INTO t VALUES (1)")).isFalse();
        }

        @Test
        void dmlShouldBeDml() {
            assertThat(parser.isDml("INSERT INTO t VALUES (1)")).isTrue();
            assertThat(parser.isDml("UPDATE t SET a=1")).isTrue();
            assertThat(parser.isDml("DELETE FROM t")).isTrue();
            assertThat(parser.isDml("SELECT 1")).isFalse();
        }
    }
}
