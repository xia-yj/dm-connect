package com.dmconnect.model;

public record QueryRequest(String sql, ExecutionMode mode, int maxRows, boolean autoCommit) {
    public QueryRequest {
        if (sql == null) throw new IllegalArgumentException("SQL 不能为空");
        if (mode == null) throw new IllegalArgumentException("执行模式不能为空");
        if (maxRows < 1) throw new IllegalArgumentException("结果行数上限必须大于 0");
    }
}
