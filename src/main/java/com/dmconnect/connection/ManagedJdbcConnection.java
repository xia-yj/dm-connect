package com.dmconnect.connection;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Holds a JDBC connection with cached health checks and optional auto-reconnect
 * when the underlying socket/session goes stale outside an open transaction.
 */
public final class ManagedJdbcConnection {
    private Connection connection;
    private final JdbcConnectionFactory connectionFactory;
    private long lastValidatedNanos;

    public ManagedJdbcConnection(Connection connection, JdbcConnectionFactory connectionFactory) {
        if (connection == null) throw new IllegalArgumentException("connection");
        this.connection = connection;
        this.connectionFactory = connectionFactory;
    }

    public Connection get() {
        return connection;
    }

    /**
     * Validates the current connection (with a short cache window). If unusable and there is
     * no pending transaction, opens a replacement via the factory and restores auto-commit.
     */
    public Connection ensureOpen(boolean closed,
                                 boolean pendingTransaction,
                                 boolean autoCommit,
                                 String closedMessage,
                                 String lostTransactionMessage) throws SQLException {
        if (closed) throw new SQLException(closedMessage);
        long validationStarted = System.nanoTime();
        boolean fullValidation = lastValidatedNanos == 0
                || validationStarted - lastValidatedNanos >= JdbcConnectionHealth.VALIDATION_CACHE_NANOS;
        if (JdbcConnectionHealth.isUsable(connection, lastValidatedNanos)) {
            if (fullValidation) lastValidatedNanos = validationStarted;
            return connection;
        }
        if (pendingTransaction) {
            throw new SQLException(lostTransactionMessage);
        }
        if (connectionFactory == null) throw new SQLException("数据库会话连接已失效");

        Connection replacement = connectionFactory.open();
        boolean ready = false;
        try {
            replacement.setAutoCommit(autoCommit);
            ready = true;
        } finally {
            if (!ready) {
                try {
                    replacement.close();
                } catch (SQLException ignored) {
                    // Best effort while recovering from a failed reconnect.
                }
            }
        }
        Connection stale = connection;
        connection = replacement;
        lastValidatedNanos = System.nanoTime();
        try {
            stale.close();
        } catch (SQLException ignored) {
            // Stale handles are often already dead.
        }
        return connection;
    }

    public void closeQuietly() {
        try {
            connection.close();
        } catch (SQLException ignored) {
            // Shutdown paths are best effort.
        }
    }
}
