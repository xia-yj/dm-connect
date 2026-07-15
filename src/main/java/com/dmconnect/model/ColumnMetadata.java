package com.dmconnect.model;

public record ColumnMetadata(String name, String label, String typeName, int jdbcType, boolean nullable) {
}
