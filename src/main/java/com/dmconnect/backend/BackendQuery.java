package com.dmconnect.backend;

import com.dmconnect.model.ResultTable;
import com.dmconnect.query.QuerySession;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class BackendQuery {
    private final String id;
    private final String profileId;
    private final BackendWorkspace workspace;
    private final QuerySession session;
    private final Map<String, ResultTable> results = new ConcurrentHashMap<>();

    BackendQuery(String profileId, BackendWorkspace workspace, QuerySession session) {
        this.id = UUID.randomUUID().toString();
        this.profileId = profileId;
        this.workspace = workspace;
        this.session = session;
    }

    String id() {
        return id;
    }

    String profileId() {
        return profileId;
    }

    QuerySession session() {
        return session;
    }

    void clearResults() {
        results.clear();
    }

    String store(ResultTable table) {
        String resultId = UUID.randomUUID().toString();
        results.put(resultId, table);
        return resultId;
    }

    ResultTable result(String resultId) {
        ResultTable table = results.get(resultId);
        if (table == null) throw new RpcException("RESULT_NOT_FOUND", "查询结果已经失效，请重新执行 SQL");
        return table;
    }

    void close() {
        results.clear();
        workspace.release(session);
        try {
            session.close();
        } catch (Exception ignored) {
            // 应用关闭或断开连接时尽力释放 JDBC 会话。
        }
    }
}
