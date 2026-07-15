package com.dmconnect.database.mysql;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.database.spi.MetadataProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.HexFormat;

/** Database adapter for MySQL Connector/J 8.x and later. */
public final class MySqlDatabaseAdapter implements DatabaseAdapter {
    public static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

    private final MySqlMetadataProvider metadataProvider = new MySqlMetadataProvider(this);
    private final MySqlDdlProvider ddlProvider = new MySqlDdlProvider(this);

    @Override
    public String id() {
        return "mysql";
    }

    @Override
    public String displayName() {
        return "MySQL";
    }

    @Override
    public int defaultPort() {
        return 3306;
    }

    @Override
    public String driverClassName() {
        return DRIVER_CLASS;
    }

    @Override
    public String buildJdbcUrl(String host, int port, Map<String, String> advancedProperties) {
        return buildJdbcUrl(host, port, "", advancedProperties);
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database,
                               Map<String, String> advancedProperties) {
        String safeHost = normalizedHost(host);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("端口无效");
        String safeDatabase = database == null ? "" : database.strip();
        if (safeDatabase.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("数据库名无效");
        }

        String base = "jdbc:mysql://" + safeHost + ":" + port + "/" + encode(safeDatabase);
        if (advancedProperties == null || advancedProperties.isEmpty()) return base;

        String query = advancedProperties.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> !DatabaseAdapter.isSensitiveProperty(entry.getKey()))
                // The application consistently treats a MySQL database as a JDBC catalog.
                // Allowing this Connector/J option to change that interpretation breaks metadata calls.
                .filter(entry -> !entry.getKey().equalsIgnoreCase("databaseTerm"))
                .filter(entry -> !entry.getKey().equalsIgnoreCase("useAffectedRows"))
                .sorted(Comparator.comparing(Map.Entry::getKey, String.CASE_INSENSITIVE_ORDER))
                .map(entry -> encode(entry.getKey().strip()) + "="
                        + encode(entry.getValue() == null ? "" : entry.getValue()))
                .collect(Collectors.joining("&"));
        return query.isEmpty() ? base : base + "?" + query;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) {
            throw new IllegalArgumentException("标识符不能为空");
        }
        return "`" + identifier.replace("`", "``") + "`";
    }

    @Override
    public String stringLiteral(String value) {
        if (value == null) return "NULL";
        // Hex keeps quotes, backslashes, NULs and newlines independent of sql_mode.
        return "CONVERT(X'" + HexFormat.of().formatHex(value.getBytes(StandardCharsets.UTF_8)) + "' USING utf8mb4)";
    }

    @Override
    public String metadataCatalog(String namespace) {
        return namespace;
    }

    @Override
    public String metadataSchema(String namespace) {
        return null;
    }

    @Override
    public void configureStreaming(PreparedStatement statement) throws SQLException {
        // Connector/J's documented row-by-row streaming sentinel. This keeps full-table
        // CSV and INSERT exports from materializing the complete result in client memory.
        statement.setFetchSize(Integer.MIN_VALUE);
    }

    @Override
    public MetadataProvider metadataProvider() {
        return metadataProvider;
    }

    @Override
    public DdlProvider ddlProvider() {
        return ddlProvider;
    }

    private static String normalizedHost(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("主机不能为空");
        String value = host.strip();
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('/') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0 || value.indexOf('@') >= 0) {
            throw new IllegalArgumentException("主机地址无效");
        }
        if (value.startsWith("[") && value.endsWith("]")) return value;
        return value.indexOf(':') >= 0 ? "[" + value + "]" : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
