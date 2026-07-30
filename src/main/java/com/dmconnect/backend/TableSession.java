package com.dmconnect.backend;

import com.dmconnect.model.DatabaseObject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

/** A table preview tab owns one JDBC connection so edits and transactions stay isolated. */
final class TableSession implements AutoCloseable {
    private final String id = UUID.randomUUID().toString();
    private final String profileId;
    private final BackendWorkspace workspace;
    private final DatabaseObject object;
    private final Connection connection;
    private final Object lock = new Object();
    private volatile boolean pendingTransaction;
    private volatile boolean closed;

    TableSession(String profileId, BackendWorkspace workspace, DatabaseObject object) throws SQLException {
        this.profileId = profileId;
        this.workspace = workspace;
        this.object = object;
        this.connection = workspace.openConnection();
        this.connection.setAutoCommit(true);
    }

    String id() { return id; }
    String profileId() { return profileId; }
    DatabaseObject object() { return object; }

    Object lock() { return lock; }

    Connection connection() throws SQLException {
        ensureOpen();
        return connection;
    }

    boolean isAutoCommit() throws SQLException {
        synchronized (lock) { ensureOpen(); return connection.getAutoCommit(); }
    }

    void setAutoCommit(boolean autoCommit) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            connection.setAutoCommit(autoCommit);
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
        if (closed || connection.isClosed()) throw new SQLException("表格会话已经关闭");
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
