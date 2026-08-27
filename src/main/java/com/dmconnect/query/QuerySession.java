package com.dmconnect.query;

import com.dmconnect.connection.JdbcConnectionFactory;
import com.dmconnect.connection.ManagedJdbcConnection;
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

/**
 * 一个 SQL 标签对应一个独立 JDBC 会话。
 */
public final class QuerySession extends AbstractSession {

    public static final int DEFAULT_RESULT_ROWS = 100;
    public static final int MAX_RESULT_ROWS = 5_000;
    public static final int[] ALLOWED_RESULT_ROWS = {100, 1_000, 5_000};
    private final AtomicReference<Statement> runningStatement = new AtomicReference<>();

    public QuerySession(Connection connection) throws SQLException {
        this(connection, null);
    }

    public QuerySession(Connection connection, JdbcConnectionFactory connectionFactory) throws SQLException {
        super(new ManagedJdbcConnection(connection, connectionFactory));
        this.managedConnection.get().setAutoCommit(true);
    }

    private Connection connection() {
        return managedConnection.get();
    }

    public ExecutionResult execute(List<String> statements, int maxRows) {
        long started = System.nanoTime();
        List<StatementOutcome> outcomes = new ArrayList<>();
        int executed = 0;
        synchronized (lock) {
            try {
                ensureOpen();
                for (int statementIndex = 0; statementIndex < statements.size(); statementIndex++) {
                    String sql = statements.get(statementIndex);
                    if (sql == null || sql.isBlank()) continue;
                    executed++;
                    if (!connection().getAutoCommit()) pendingTransaction = true;
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
        try (Statement statement = connection().createStatement()) {
            runningStatement.set(statement);
            int resultLimit = clampResultRows(maxRows);
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
        super.setAutoCommit(autoCommit);
    }

    public boolean isAutoCommit() throws SQLException {
        return super.isAutoCommit();
    }

    public void commit() throws SQLException {
        super.commit();
    }

    public void rollback() throws SQLException {
        super.rollback();
    }

    public boolean hasPendingTransaction() {
        return super.hasPendingTransaction();
    }

    /** Reads the current MySQL session mode so script splitting follows quote semantics. */
    public Set<String> mysqlSqlModes() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            try (Statement statement = connection().createStatement();
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

    @Override
    protected String getCloseMessage() {
        return "SQL 会话已经关闭";
    }

    @Override
    protected String getLostTransactionMessage() {
        return "数据库连接已失效，未提交事务无法自动恢复；请关闭此 SQL 标签后重新打开";
    }

    public static int clampResultRows(int requested) {
        if (requested <= DEFAULT_RESULT_ROWS) return DEFAULT_RESULT_ROWS;
        if (requested <= 1_000) return 1_000;
        return MAX_RESULT_ROWS;
    }

    private static long elapsedMillis(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    private static String safeMessage(SQLException exception) {
        return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
