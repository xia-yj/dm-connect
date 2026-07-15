package com.dmconnect.ui;

import com.dmconnect.AppContext;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.query.QuerySession;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class ConnectedProfile implements AutoCloseable {
    private final AppContext context;
    private final ConnectionProfile profile;
    private final char[] password;
    private final Set<QuerySession> sessions = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    ConnectedProfile(AppContext context, ConnectionProfile profile, char[] password) {
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

    QuerySession openSession() throws SQLException {
        QuerySession session = new QuerySession(openConnection());
        sessions.add(session);
        return session;
    }

    void releaseSession(QuerySession session) {
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
                // 断开连接时尽力关闭全部标签会话。
            }
        });
        sessions.clear();
        Arrays.fill(password, '\0');
    }
}
