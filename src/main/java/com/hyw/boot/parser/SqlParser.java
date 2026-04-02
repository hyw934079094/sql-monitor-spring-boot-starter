package com.hyw.boot.parser;

import com.hyw.boot.parser.JSqlParser.SqlParseResult;

import java.util.List;

/**
 * SQL解析器接口
 *
 * @author hyw
 * @version 3.0.0
 */
public interface SqlParser {

    /**
     * 解析SQL语句
     *
     * @param sql 原始SQL语句
     * @return 解析结果
     */
    SqlParseResult parse(String sql);

    /**
     * 获取SQL类型（SELECT/INSERT/UPDATE/DELETE等）
     *
     * @param sql 原始SQL语句
     * @return SQL类型
     */
    String getSqlType(String sql);

    /**
     * 提取SQL中涉及的表名
     *
     * @param sql 原始SQL语句
     * @return 表名列表
     */
    List<String> extractTableNames(String sql);

    /**
     * 判断是否为SELECT查询
     *
     * @param sql 原始SQL语句
     * @return true如果是SELECT查询
     */
    boolean isSelect(String sql);

    /**
     * 判断是否为DML操作（INSERT/UPDATE/DELETE/MERGE）
     *
     * @param sql 原始SQL语句
     * @return true如果是DML操作
     */
    boolean isDml(String sql);
}