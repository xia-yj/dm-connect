package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.connection.ManagedJdbcConnection;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.query.QuerySession;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class BackendWorkspace implements AutoCloseable {
    private final AppContext context;
    private final ConnectionProfile profile;
    private final char[] password;
    private final Set<QuerySession> sessions = ConcurrentHashMap.newKeySet();
    private final Object metadataLock = new Object();
    private ManagedJdbcConnection metadataConnection;
    private volatile boolean closed;

    @FunctionalInterface
    interface SharedJdbcAction<T> {
        T apply(Connection connection) throws Exception;
    }

    BackendWorkspace(AppContext context, ConnectionProfile profile, char[] password) {
        this.context = context;
        this.profile = profile;
        this.password = password.clone();
    }

    ConnectionProfile profile() {
        return profile;
    }

    Connection openConnection() throws SQLException {
        if (closed) throw new SQLException("连接已断开");
        char[] copy = password.clone();
        try {
            return context.connections().connect(profile, copy);
        } finally {
            Arrays.fill(copy, '\0');
        }
    }

    /**
     * Reuses one auto-commit JDBC connection for object browsing and short metadata writes.
     * SQL tabs and table editors still call {@link #openConnection()} for isolated sessions.
     */
    <T> T withSharedConnection(SharedJdbcAction<T> action) throws Exception {
        synchronized (metadataLock) {
            return action.apply(ensureSharedConnection());
        }
    }

    QuerySession openSession() throws SQLException {
        QuerySession session = new QuerySession(openConnection(), this::openConnection);
        sessions.add(session);
        return session;
    }

    void release(QuerySession session) {
        sessions.remove(session);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        sessions.forEach(session -> {
            try {
                session.close();
            } catch (SQLException ignored) {
                // 断开连接时尽力关闭全部查询会话。
            }
        });
        sessions.clear();
        synchronized (metadataLock) {
            if (metadataConnection != null) {
                metadataConnection.closeQuietly();
                metadataConnection = null;
            }
        }
        Arrays.fill(password, '\0');
    }

    private Connection ensureSharedConnection() throws SQLException {
        if (closed) throw new SQLException("连接已断开");
        if (metadataConnection == null) {
            metadataConnection = new ManagedJdbcConnection(openConnection(), this::openConnection);
        }
        return metadataConnection.ensureOpen(
                closed,
                false,
                true,
                "连接已断开",
                "数据库会话连接已失效");
    }
}
