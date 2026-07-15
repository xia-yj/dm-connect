package com.dmconnect.model;

import java.util.List;

/** AND-combined equality or LIKE conditions applied to a table preview. */
public record PreviewFilter(List<Condition> conditions) {
    public PreviewFilter {
        conditions = List.copyOf(conditions);
        if (conditions.isEmpty()) throw new IllegalArgumentException("筛选条件不能为空");
    }

    public record Condition(String column, String operator, String value) {
        public Condition {
        if (column == null || column.isBlank()) throw new IllegalArgumentException("筛选列不能为空");
        if (!"=".equals(operator) && !"LIKE".equals(operator)) throw new IllegalArgumentException("不支持的筛选运算符");
        if (value == null) throw new IllegalArgumentException("筛选值不能为空");
        }
    }
}
