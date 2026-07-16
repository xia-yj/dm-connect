package com.dmconnect.model;

public record ColumnInfo(
        int ordinal,
        String name,
        String typeName,
        int size,
        int scale,
        boolean nullable,
        String defaultValue,
        boolean autoIncrement,
        String onUpdateExpression,
        String remarks,
        boolean safelyEditable,
        String editWarning) {
}
