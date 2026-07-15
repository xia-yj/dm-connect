package com.dmconnect.query;

import com.dmconnect.model.ExecutionResult;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.StatementOutcome;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/** 一个 SQL 标签对应一个独立 JDBC 会话。 */
public final class QuerySession implements AutoCloseable {
    /** 查询结果的绝对上限，避免任意 SQL 将大量数据加载到客户端。 */
    public static final int MAX_RESULT_ROWS = 100;
    private final Connection connection;
    private final Object transactionLock = new Object();
    private final AtomicReference<Statement> runningStatement = new AtomicReference<>();
    private volatile boolean pendingTransaction;
    private volatile boolean closed;

    public QuerySession(Connection connection) throws SQLException {
        this.connection = connection;
        this.connection.setAutoCommit(true);
    }

    public ExecutionResult execute(List<String> statements, int maxRows) {
        long started = System.nanoTime();
        List<StatementOutcome> outcomes = new ArrayList<>();
        int executed = 0;
        synchronized (transactionLock) {
            try {
                ensureOpen();
                for (int statementIndex = 0; statementIndex < statements.size(); statementIndex++) {
                    String sql = statements.get(statementIndex);
                    if (sql == null || sql.isBlank()) continue;
                    executed++;
                    if (!connection.getAutoCommit()) pendingTransaction = true;
                    executeOne(sql, statementIndex + 1, maxRows, outcomes);
                }
                return new ExecutionResult(outcomes, true, "", "", 0, elapsedMillis(started), executed);
            } catch (SQLException exception) {
                return new ExecutionResult(outcomes, false, safeMessage(exception), exception.getSQLState(),
                        exception.getErrorCode(), elapsedMillis(started), executed);
            }
        }
    }

    private void executeOne(String sql, int statementIndex, int maxRows,
                            List<StatementOutcome> outcomes) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            runningStatement.set(statement);
            int resultLimit = Math.min(maxRows, MAX_RESULT_ROWS);
            // 多取一行仅用于标记“已截断”，不会把完整结果集传回客户端。
            statement.setMaxRows(resultLimit + 1);
            statement.setFetchSize(resultLimit + 1);
            boolean hasResultSet = statement.execute(sql);
            int resultIndex = 1;
            while (true) {
                if (hasResultSet) {
                    try (ResultSet resultSet = statement.getResultSet()) {
                        ResultTable table = ResultSetReader.read(resultSet, resultLimit);
                        outcomes.add(StatementOutcome.table(statementIndex, resultIndex, table));
                    }
                } else {
                    int updateCount = statement.getUpdateCount();
                    if (updateCount == -1) break;
                    outcomes.add(StatementOutcome.update(statementIndex, resultIndex, updateCount));
                }
                hasResultSet = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
                if (!hasResultSet && statement.getUpdateCount() == -1) break;
                resultIndex++;
            }
        } finally {
            runningStatement.set(null);
        }
    }

    public void cancel() throws SQLException {
        Statement statement = runningStatement.get();
        if (statement != null) statement.cancel();
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        synchronized (transactionLock) {
            ensureOpen();
            connection.setAutoCommit(autoCommit);
            if (autoCommit) pendingTransaction = false;
        }
    }

    public boolean isAutoCommit() throws SQLException {
        synchronized (transactionLock) {
            ensureOpen();
            return connection.getAutoCommit();
        }
    }

    public void commit() throws SQLException {
        synchronized (transactionLock) {
            ensureOpen();
            connection.commit();
            pendingTransaction = false;
        }
    }

    public void rollback() throws SQLException {
        synchronized (transactionLock) {
            ensureOpen();
            connection.rollback();
            pendingTransaction = false;
        }
    }

    public boolean hasPendingTransaction() {
        return pendingTransaction;
    }

    /** Reads the current MySQL session mode so script splitting follows quote semantics. */
    public Set<String> mysqlSqlModes() throws SQLException {
        synchronized (transactionLock) {
            ensureOpen();
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery("SELECT @@SESSION.sql_mode")) {
                if (!rows.next()) return Set.of();
                String modes = rows.getString(1);
                if (modes == null || modes.isBlank()) return Set.of();
                return java.util.Arrays.stream(modes.toUpperCase(Locale.ROOT).split(","))
                        .map(String::strip)
                        .filter(mode -> !mode.isEmpty())
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
            }
        }
    }

    private void ensureOpen() throws SQLException {
        if (closed || connection.isClosed()) throw new SQLException("SQL 会话已经关闭");
    }

    @Override
    public void close() throws SQLException {
        synchronized (transactionLock) {
            if (closed) return;
            try {
                if (pendingTransaction && !connection.getAutoCommit()) connection.rollback();
            } finally {
                closed = true;
                connection.close();
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String safeMessage(SQLException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
