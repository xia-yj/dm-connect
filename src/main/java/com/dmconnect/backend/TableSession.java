package com.dmconnect.backend;

import com.dmconnect.connection.JdbcConnectionFactory;
import com.dmconnect.connection.ManagedJdbcConnection;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.query.AbstractSession;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.UUID;

/**
 * A table preview tab owns one JDBC connection so edits and transactions stay isolated.
 */
public final class TableSession extends AbstractSession {

    private final String id = UUID.randomUUID().toString();
    private final String profileId;
    private final DatabaseObject object;

    TableSession(String profileId, BackendWorkspace workspace, DatabaseObject object) throws SQLException {
        this(profileId, object, workspace::openConnection);
    }

    TableSession(String profileId, DatabaseObject object, JdbcConnectionFactory connectionFactory) throws SQLException {
        super(new ManagedJdbcConnection(connectionFactory.open(), connectionFactory));
        this.managedConnection.get().setAutoCommit(true);
        this.profileId = profileId;
        this.object = object;
    }

    String id() { return id; }
    String profileId() { return profileId; }
    DatabaseObject object() { return object; }

    Object lock() { return lock; }

    Connection connection() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            return managedConnection.get();
        }
    }

    @Override
    public boolean isAutoCommit() throws SQLException {
        return super.isAutoCommit();
    }

    @Override
    public void setAutoCommit(boolean autoCommit) throws SQLException {
        super.setAutoCommit(autoCommit);
    }

    void markPendingIfNeeded() throws SQLException {
        synchronized (lock) {
            ensureOpen();
            if (!managedConnection.get().getAutoCommit()) pendingTransaction = true;
        }
    }

    @Override
    public boolean hasPendingTransaction() {
        return super.hasPendingTransaction();
    }

    @Override
    public void commit() throws SQLException {
        super.commit();
    }

    @Override
    public void rollback() throws SQLException {
        super.rollback();
    }

    Savepoint savepoint(String name) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            return managedConnection.get().setSavepoint(name);
        }
    }

    void rollback(Savepoint savepoint) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            managedConnection.get().rollback(savepoint);
        }
    }

    void releaseSavepoint(Savepoint savepoint) throws SQLException {
        synchronized (lock) {
            ensureOpen();
            managedConnection.get().releaseSavepoint(savepoint);
        }
    }

    @Override
    protected String getCloseMessage() {
        return "表格会话已经关闭";
    }

    @Override
    protected String getLostTransactionMessage() {
        return "数据库连接已失效，未提交事务无法自动恢复；请关闭此表格标签后重新打开";
    }
}
