package com.dmconnect.backend;

import com.dmconnect.connection.JdbcConnectionFactory;
import com.dmconnect.connection.JdbcConnectionHealth;
import com.dmconnect.model.DatabaseObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

/** A table preview tab owns one JDBC connection so edits and transactions stay isolated. */
final class TableSession implements AutoCloseable {
    private final String id = UUID.randomUUID().toString();
    private final String profileId;
    private final DatabaseObject object;
    private final JdbcConnectionFactory connectionFactory;
    private Connection connection;
    private final Object lock = new Object();
    private volatile boolean pendingTransaction;
    private volatile boolean autoCommit = true;
    private volatile boolean closed;
    private long lastValidatedNanos;

    TableSession(String profileId, BackendWorkspace workspace, DatabaseObject object) throws SQLException {
        this(profileId, object, workspace::openConnection);
    }

    TableSession(String profileId, DatabaseObject object, JdbcConnectionFactory connectionFactory) throws SQLException {
        this.profileId = profileId;
        this.object = object;
        this.connectionFactory = connectionFactory;
        this.connection = connectionFactory.open();
        this.connection.setAutoCommit(true);
    }

    String id() { return id; }
    String profileId() { return profileId; }
    DatabaseObject object() { return object; }

    Object lock() { return lock; }

    Connection connection() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            return connection;
        }
    }

    boolean isAutoCommit() throws SQLException {
        synchronized (lock) { ensureOpen(); return autoCommit; }
    }

    void setAutoCommit(boolean autoCommit) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            connection.setAutoCommit(autoCommit);
            this.autoCommit = autoCommit;
            if (autoCommit) pendingTransaction = false;
        }
    }

    void markPendingIfNeeded() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            if (!connection.getAutoCommit()) pendingTransaction = true;
        }
    }

    boolean hasPendingTransaction() { return pendingTransaction; }

    void commit() throws SQLException {
        synchronized (lock) { ensureOpen(); connection.commit(); pendingTransaction = false; }
    }

    void rollback() throws SQLException {
        synchronized (lock) { ensureOpen(); connection.rollback(); pendingTransaction = false; }
    }

    Savepoint savepoint(String name) throws SQLException {
        synchronized (lock) { ensureOpen(); return connection.setSavepoint(name); }
    }

    void rollback(Savepoint savepoint) throws SQLException {
        synchronized (lock) { ensureOpen(); connection.rollback(savepoint); }
    }

    void releaseSavepoint(Savepoint savepoint) throws SQLException {
        synchronized (lock) { if (!closed) connection.releaseSavepoint(savepoint); }
    }

    private void ensureOpen() throws SQLException {
        if (closed) throw new SQLException("表格会话已经关闭");
        long validationStarted = System.nanoTime();
        boolean fullValidation = lastValidatedNanos == 0
                || validationStarted - lastValidatedNanos >= JdbcConnectionHealth.VALIDATION_CACHE_NANOS;
        if (JdbcConnectionHealth.isUsable(connection, lastValidatedNanos)) {
            if (fullValidation) lastValidatedNanos = validationStarted;
            return;
        }
        if (pendingTransaction) {
            throw new SQLException("数据库连接已失效，未提交事务无法自动恢复；请关闭此表格标签后重新打开");
        }

        Connection replacement = connectionFactory.open();
        boolean ready = false;
        try {
            replacement.setAutoCommit(autoCommit);
            ready = true;
        } finally {
            if (!ready) try { replacement.close(); } catch (SQLException ignored) { }
        }
        Connection stale = connection;
        connection = replacement;
        lastValidatedNanos = System.nanoTime();
        try { stale.close(); } catch (SQLException ignored) { }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            try {
                if (pendingTransaction && !connection.getAutoCommit()) connection.rollback();
            } catch (SQLException ignored) {
                // Best effort during tab/profile shutdown.
            } finally {
                closed = true;
                try { connection.close(); } catch (SQLException ignored) { }
            }
        }
    }
}
