package com.dmconnect.query;

public record ParsedStatement(String sql, int startOffset, int endOffset) {
}
