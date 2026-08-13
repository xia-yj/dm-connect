package com.dmconnect.query;

import com.dmconnect.connection.ManagedJdbcConnection;
import java.sql.SQLException;

/**
 * 抽象会话类，提取 QuerySession 和 TableSession 共用的连接管理逻辑。
 * 包括 ensureOpen、重连机制、事务处理、关闭等。
 */
public abstract class AbstractSession implements AutoCloseable {

    protected final ManagedJdbcConnection managedConnection;
    protected final Object lock = new Object();
    protected volatile boolean pendingTransaction;
    protected volatile boolean autoCommit = true;
    protected volatile boolean closed;

    protected AbstractSession(ManagedJdbcConnection managedConnection, boolean initialAutoCommit) {
        if (managedConnection == null) {
            throw new IllegalArgumentException("managedConnection");
        }
        this.managedConnection = managedConnection;
        this.autoCommit = initialAutoCommit;
    }

    protected AbstractSession(ManagedJdbcConnection managedConnection) {
        this(managedConnection, true);
    }

    /**
     * 确保连接打开（带自动重连）。由子类实现具体消息。
     */
    protected void ensureOpen() throws SQLException {
        managedConnection.ensureOpen(
                closed,
                pendingTransaction,
                autoCommit,
                getCloseMessage(),
                getLostTransactionMessage()
        );
    }

    /**
     * 关闭消息（子类实现）。
     */
    protected abstract String getCloseMessage();

    /**
     * 未提交事务导致连接失效时的错误消息（子类实现）。
     */
    protected abstract String getLostTransactionMessage();

    public boolean hasPendingTransaction() {
        return pendingTransaction;
    }

    public boolean isAutoCommit() throws SQLException {
        ensureOpen();
        return autoCommit;
    }

    public void setAutoCommit(boolean autoCommit) throws SQLException {
        ensureOpen();
        managedConnection.get().setAutoCommit(autoCommit);
        this.autoCommit = autoCommit;
        if (autoCommit) {
            pendingTransaction = false;
        }
    }

    public void commit() throws SQLException {
        ensureOpen();
        managedConnection.get().commit();
        pendingTransaction = false;
    }

    public void rollback() throws SQLException {
        ensureOpen();
        managedConnection.get().rollback();
        pendingTransaction = false;
    }

    @Override
    public void close() throws SQLException {
        synchronized (lock) {
            if (closed) return;
            try {
                if (pendingTransaction && !managedConnection.get().getAutoCommit()) {
                    managedConnection.get().rollback();
                }
            } catch (SQLException ignored) {
                // Best effort
            } finally {
                closed = true;
                managedConnection.closeQuietly();
            }
        }
    }
}
