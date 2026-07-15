package com.dmconnect.nativeclient;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.database.spi.MetadataProvider;

import java.util.Map;

/** Marks a profile handled by a native client instead of JDBC. */
public final class NativeDatabaseAdapter implements DatabaseAdapter {
    private final String id, name, clientClass;
    private final int port;
    public NativeDatabaseAdapter(String id, String name, int port, String clientClass) { this.id = id; this.name = name; this.port = port; this.clientClass = clientClass; }
    @Override public String id() { return id; }
    @Override public String displayName() { return name; }
    @Override public int defaultPort() { return port; }
    @Override public String driverClassName() { return clientClass; }
    @Override public String buildJdbcUrl(String host, int port, Map<String, String> properties) { return buildJdbcUrl(host, port, "", properties); }
    @Override public String buildJdbcUrl(String host, int port, String database, Map<String, String> properties) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("主机不能为空");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("端口无效");
        return id + "://" + host.strip() + ":" + port + "/" + (database == null ? "" : database.strip());
    }
    @Override public String quoteIdentifier(String identifier) { throw new UnsupportedOperationException("非 SQL 数据库没有 SQL 标识符"); }
    @Override public MetadataProvider metadataProvider() { throw new UnsupportedOperationException("非 SQL 数据库不使用 JDBC 元数据"); }
    @Override public DdlProvider ddlProvider() { throw new UnsupportedOperationException("非 SQL 数据库不支持 DDL"); }
}
