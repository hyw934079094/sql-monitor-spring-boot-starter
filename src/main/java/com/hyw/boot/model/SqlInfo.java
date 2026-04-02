package com.hyw.boot.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * SQL信息模型
 *
 * @author hyw
 * @version 3.0.0
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SqlInfo {
    private String sqlId;
    private String filteredSql;
    private String sqlType;
    private List<String> tables;
    private String whereCondition;
    private String params;
    private boolean hasJoin;
    private boolean hasSubQuery;
    private String error;
    private String dbType;
    private String traceId;
    private String userId;
}
