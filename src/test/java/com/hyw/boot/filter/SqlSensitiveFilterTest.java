package com.hyw.boot.filter;

import com.hyw.boot.config.SlowSqlProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SqlSensitiveFilter 单元测试
 */
class SqlSensitiveFilterTest {

    private SlowSqlProperties properties;
    private SqlSensitiveFilter filter;

    @BeforeEach
    void setUp() {
        properties = new SlowSqlProperties();
        properties.getSensitive().setEnabled(true);
        properties.getSensitive().setSensitiveFields(
                Arrays.asList("password", "phone", "id_card", "token"));
        properties.getSensitive().setSensitiveTables(
                Arrays.asList("user", "account", "payment"));
        filter = new SqlSensitiveFilter(properties);
        filter.initPattern();
    }

    // ====================================================================
    // 敏感字段脱敏
    // ====================================================================

    @Nested
    @DisplayName("敏感字段脱敏")
    class SensitiveFieldTests {

        @Test
        @DisplayName("单引号字段值脱敏")
        void shouldMaskSingleQuotedFieldValue() {
            String sql = "SELECT * FROM orders WHERE password='mySecret123'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("mySecret123");
            assertThat(result).contains("password=");
        }

        @Test
        @DisplayName("双引号字段值脱敏")
        void shouldMaskDoubleQuotedFieldValue() {
            String sql = "SELECT * FROM orders WHERE token=\"abc-token-xyz\"";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("abc-token-xyz");
            assertThat(result).contains("token=");
        }

        @Test
        @DisplayName("多个敏感字段同时脱敏")
        void shouldMaskMultipleSensitiveFields() {
            String sql = "SELECT * FROM orders WHERE password='secret' AND token='tk123'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("secret");
            assertThat(result).doesNotContain("tk123");
        }

        @Test
        @DisplayName("大小写不敏感")
        void shouldBeCaseInsensitive() {
            String sql = "SELECT * FROM orders WHERE PASSWORD='secret'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("secret");
        }

        @Test
        @DisplayName("非敏感字段不受影响")
        void shouldNotMaskNonSensitiveFields() {
            String sql = "SELECT * FROM orders WHERE name='visible'";
            String result = filter.filter(sql);
            assertThat(result).contains("name='visible'");
        }
    }

    // ====================================================================
    // 敏感表全脱敏
    // ====================================================================

    @Nested
    @DisplayName("敏感表全脱敏")
    class SensitiveTableTests {

        @Test
        @DisplayName("INSERT VALUES 脱敏")
        void shouldMaskInsertValues() {
            String sql = "INSERT INTO user (name, pwd) VALUES ('admin', 'secret')";
            String result = filter.filter(sql);
            assertThat(result).contains("VALUES (***)");
            assertThat(result).doesNotContain("admin");
            assertThat(result).doesNotContain("secret");
        }

        @Test
        @DisplayName("多行 INSERT VALUES 全部脱敏（DOTALL 覆盖）")
        void shouldMaskMultiLineInsertValues() {
            String sql = "INSERT INTO user (name, pwd) VALUES\n('admin', 'secret'),\n('user2', 'pass2')";
            String result = filter.filter(sql);
            assertThat(result).contains("VALUES (***)");
            assertThat(result).doesNotContain("admin");
            assertThat(result).doesNotContain("secret");
            assertThat(result).doesNotContain("user2");
            assertThat(result).doesNotContain("pass2");
        }

        @Test
        @DisplayName("UPDATE SET 脱敏")
        void shouldMaskUpdateSet() {
            String sql = "UPDATE user SET name='newName', pwd='newPwd' WHERE id=1";
            String result = filter.filter(sql);
            assertThat(result).contains("SET ***");
            assertThat(result).doesNotContain("newName");
            assertThat(result).doesNotContain("newPwd");
        }

        @Test
        @DisplayName("多行 UPDATE SET 全部脱敏")
        void shouldMaskMultiLineUpdateSet() {
            String sql = "UPDATE user SET\n  name='newName',\n  pwd='newPwd'\nWHERE id=1";
            String result = filter.filter(sql);
            assertThat(result).contains("SET ***");
            assertThat(result).doesNotContain("newName");
            assertThat(result).doesNotContain("newPwd");
        }

        @Test
        @DisplayName("WHERE 字面值脱敏")
        void shouldMaskWhereValues() {
            String sql = "SELECT * FROM user WHERE name='admin' AND status='active'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("admin");
            assertThat(result).doesNotContain("active");
            assertThat(result).contains("='***'");
        }

        @Test
        @DisplayName("非敏感表不触发全脱敏")
        void shouldNotMaskNonSensitiveTable() {
            String sql = "INSERT INTO orders (item) VALUES ('laptop')";
            String result = filter.filter(sql);
            assertThat(result).contains("laptop");
        }
    }

    // ====================================================================
    // 手机号 & 身份证脱敏
    // ====================================================================

    @Nested
    @DisplayName("手机号和身份证脱敏")
    class PhoneAndIdCardTests {

        @Test
        @DisplayName("手机号脱敏：中间 4 位替换为 ****")
        void shouldMaskPhoneNumber() {
            String sql = "SELECT * FROM orders WHERE contact='13812345678'";
            String result = filter.filter(sql);
            assertThat(result).contains("138****5678");
            assertThat(result).doesNotContain("13812345678");
        }

        @Test
        @DisplayName("身份证脱敏：中间 8 位替换")
        void shouldMaskIdCard() {
            String sql = "SELECT * FROM orders WHERE id_no='110101199001011234'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("110101199001011234");
            assertThat(result).contains("110101");
            assertThat(result).contains("1234");
        }

        @Test
        @DisplayName("身份证末位 X 支持")
        void shouldMaskIdCardWithX() {
            String sql = "SELECT * FROM orders WHERE id_no='11010119900101123X'";
            String result = filter.filter(sql);
            assertThat(result).doesNotContain("11010119900101123X");
        }
    }

    // ====================================================================
    // 边界场景
    // ====================================================================

    @Nested
    @DisplayName("边界场景")
    class EdgeCaseTests {

        @Test
        @DisplayName("null SQL 返回 null")
        void shouldReturnNullForNullInput() {
            assertThat(filter.filter((String) null)).isNull();
        }

        @Test
        @DisplayName("空字符串直接返回")
        void shouldReturnEmptyForEmptyInput() {
            assertThat(filter.filter("")).isEmpty();
        }

        @Test
        @DisplayName("脱敏关闭时直接返回原始 SQL")
        void shouldReturnOriginalWhenDisabled() {
            properties.getSensitive().setEnabled(false);
            String sql = "SELECT * FROM user WHERE password='secret'";
            assertThat(filter.filter(sql)).isEqualTo(sql);
        }

        @Test
        @DisplayName("Object 脱敏入口")
        void shouldFilterObjectInput() {
            assertThat(filter.filter((Object) null)).isEqualTo("null");
            assertThat(filter.filter(12345)).isEqualTo("12345");
        }
    }
}
