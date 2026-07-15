package com.dmconnect.model;

import java.util.List;

public record ExecutionResult(
        List<StatementOutcome> outcomes,
        boolean success,
        String errorMessage,
        String sqlState,
        int vendorCode,
        long durationMillis,
        int executedStatements) {

    public ExecutionResult {
        outcomes = List.copyOf(outcomes);
        errorMessage = errorMessage == null ? "" : errorMessage;
        sqlState = sqlState == null ? "" : sqlState;
    }
}
