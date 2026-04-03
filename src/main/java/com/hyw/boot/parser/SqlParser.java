package com.hyw.boot.parser;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SQL解析器接口
 *
 * @author hyw
 * @version 3.0.0
 */
public interface SqlParser {

    /**
     * 解析SQL
     *
     * @param sql SQL语句
     * @return 解析结果
     */
    SqlParseResult parse(String sql);

    /**
     * 获取SQL类型
     *
     * @param sql SQL语句
     * @return SQL类型
     */
    String getSqlType(String sql);

    /**
     * 提取表名
     *
     * @param sql SQL语句
     * @return 表名列表
     */
    List<String> extractTableNames(String sql);

    /**
     * 判断是否为SELECT
     *
     * @param sql SQL语句
     * @return true如果是SELECT
     */
    boolean isSelect(String sql);

    /**
     * 判断是否为DML操作
     *
     * @param sql SQL语句
     * @return true如果是DML操作
     */
    boolean isDml(String sql);

    /**
     * SQL解析结果
     */
    @Data
    class SqlParseResult {
        private String originalSql;
        private String sqlType;
        private List<String> tables = new ArrayList<>();
        private List<String> columns = new ArrayList<>();
        private String whereCondition;
        private String orderBy;
        private String limit;
        private boolean hasJoin;
        private boolean hasSubQuery;
    }
}
