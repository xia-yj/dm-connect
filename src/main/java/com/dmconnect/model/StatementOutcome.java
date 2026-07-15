package com.dmconnect.model;

public record StatementOutcome(int statementIndex, int resultIndex, ResultTable table, Integer updateCount) {
    public static StatementOutcome table(int statementIndex, int resultIndex, ResultTable table) {
        return new StatementOutcome(statementIndex, resultIndex, table, null);
    }

    public static StatementOutcome update(int statementIndex, int resultIndex, int updateCount) {
        return new StatementOutcome(statementIndex, resultIndex, null, updateCount);
    }

    public boolean hasTable() {
        return table != null;
    }
}
