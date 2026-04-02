package com.hyw.boot.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 慢SQL日志模型
 *
 * @author hyw
 * @version 3.0.0
 */
@Data
@Builder
public class SlowSqlLog {
    private long timestamp;
    private String threadName;
    private String sqlId;
    private long cost;
    private String method;
    private String sqlType;
    private List<String> tables;
    private String sql;
    private String params;
    private boolean hasJoin;
    private boolean hasSubQuery;
    private String whereCondition;
    private boolean hasError;
    private String errorMessage;
    private String traceId;
    private String userId;
}