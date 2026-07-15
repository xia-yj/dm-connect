package com.dmconnect.database.generic;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.database.spi.MetadataProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Shared JDBC adapter for engines whose initial support relies on standard JDBC metadata. */
public final class GenericJdbcAdapter implements DatabaseAdapter {
    private static final Pattern ORACLE_SERVICE = Pattern.compile("[A-Za-z0-9_.$#-]+");
    private static final Pattern PROPERTY_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9._-]*");
    private static final Set<String> CORE_PROPERTIES = Set.of(
            "host", "port", "database", "servername", "portnumber", "databasename");

    public enum Flavor { POSTGRESQL, ORACLE, SQLSERVER, SQLITE }

    private final String id;
    private final String name;
    private final String driver;
    private final int port;
    private final Flavor flavor;
    private final GenericJdbcMetadata metadata = new GenericJdbcMetadata(this);
    private final GenericJdbcDdl ddl = new GenericJdbcDdl(this);

    public GenericJdbcAdapter(String id, String name, String driver, int port, Flavor flavor) {
        this.id = id;
        this.name = name;
        this.driver = driver;
        this.port = port;
        this.flavor = flavor;
    }

    @Override public String id() { return id; }
    @Override public String displayName() { return name; }
    @Override public int defaultPort() { return port; }
    @Override public String driverClassName() { return driver; }
    public Flavor flavor() { return flavor; }

    @Override
    public String buildJdbcUrl(String host, int port, Map<String, String> properties) {
        return buildJdbcUrl(host, port, "", properties);
    }

    @Override
    public String buildJdbcUrl(String host, int port, String database, Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        if (flavor == Flavor.SQLITE) {
            if (database == null || database.isBlank()) throw new IllegalArgumentException("SQLite 需要数据库文件路径");
            if (!safeProperties.isEmpty()) throw new IllegalArgumentException("SQLite 首版不支持高级 JDBC 参数");
            return "jdbc:sqlite:" + database.strip();
        }
        connectionProperties(safeProperties);

        String safeHost = normalizedHost(host);
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("端口无效");
        String safeDatabase = database == null ? "" : database.strip();
        if (safeDatabase.isEmpty()) throw new IllegalArgumentException(flavor == Flavor.ORACLE ? "Oracle 服务名不能为空" : "数据库名不能为空");
        if (safeDatabase.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("数据库名无效");

        return switch (flavor) {
            case POSTGRESQL -> "jdbc:postgresql://" + safeHost + ":" + port + "/" + encode(safeDatabase);
            case ORACLE -> {
                if (!ORACLE_SERVICE.matcher(safeDatabase).matches()) throw new IllegalArgumentException("Oracle 服务名无效");
                yield "jdbc:oracle:thin:@//" + safeHost + ":" + port + "/" + safeDatabase;
            }
            case SQLSERVER -> sqlServerUrl(safeHost, port, safeDatabase);
            case SQLITE -> throw new IllegalStateException();
        };
    }

    @Override
    public Map<String, String> connectionProperties(Map<String, String> properties) {
        Map<String, String> safeProperties = properties == null ? Map.of() : properties;
        if (flavor == Flavor.SQLITE) {
            if (!safeProperties.isEmpty()) throw new IllegalArgumentException("SQLite 首版不支持高级 JDBC 参数");
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>();
        safeProperties.entrySet().stream()
                .sorted((left, right) -> String.CASE_INSENSITIVE_ORDER.compare(
                        left.getKey() == null ? "" : left.getKey(),
                        right.getKey() == null ? "" : right.getKey()))
                .forEach(entry -> {
                    String key = entry.getKey() == null ? "" : entry.getKey().strip();
                    String value = entry.getValue() == null ? "" : entry.getValue();
                    if (!PROPERTY_NAME.matcher(key).matches()) throw new IllegalArgumentException("JDBC 参数名无效：" + key);
                    if (DatabaseAdapter.isSensitiveProperty(key)) throw new IllegalArgumentException("凭据不能写入高级 JDBC 参数：" + key);
                    if (CORE_PROPERTIES.contains(key.toLowerCase(java.util.Locale.ROOT))) {
                        throw new IllegalArgumentException("高级 JDBC 参数不能覆盖核心连接字段：" + key);
                    }
                    if (value.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("JDBC 参数值不能包含控制字符：" + key);
                    if (result.keySet().stream().anyMatch(existing -> existing.equalsIgnoreCase(key))) {
                        throw new IllegalArgumentException("高级 JDBC 参数重复：" + key);
                    }
                    result.put(key, value);
                });
        return Map.copyOf(result);
    }

    @Override
    public String quoteIdentifier(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("标识符不能为空");
        return flavor == Flavor.SQLSERVER
                ? "[" + value.replace("]", "]]" ) + "]"
                : "\"" + value.replace("\"", "\"\"") + "\"";
    }

    @Override public String metadataCatalog(String namespace) { return null; }
    @Override public String metadataSchema(String namespace) { return flavor == Flavor.SQLITE ? null : namespace; }
    @Override public MetadataProvider metadataProvider() { return metadata; }
    @Override public DdlProvider ddlProvider() { return ddl; }

    private static String sqlServerUrl(String host, int port, String database) {
        return new StringBuilder("jdbc:sqlserver://").append(host).append(':').append(port)
                .append(";databaseName=").append(sqlServerValue(database)).toString();
    }

    private static String sqlServerValue(String value) {
        if (value.matches("[A-Za-z0-9._-]+")) return value;
        return "{" + value.replace("}", "}}") + "}";
    }

    private static String normalizedHost(String host) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("主机不能为空");
        String value = host.strip();
        if (value.chars().anyMatch(Character::isWhitespace)
                || value.indexOf('/') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0 || value.indexOf('@') >= 0
                || value.indexOf(';') >= 0) {
            throw new IllegalArgumentException("主机地址无效");
        }
        boolean startsBracket = value.startsWith("[");
        boolean endsBracket = value.endsWith("]");
        if (startsBracket || endsBracket) {
            if (!startsBracket || !endsBracket || value.length() <= 2
                    || value.substring(1, value.length() - 1).indexOf('[') >= 0
                    || value.substring(1, value.length() - 1).indexOf(']') >= 0) {
                throw new IllegalArgumentException("主机地址无效");
            }
            return value;
        }
        long colons = value.chars().filter(character -> character == ':').count();
        if (colons == 1) throw new IllegalArgumentException("主机地址请勿包含端口");
        return colons > 1 ? "[" + value + "]" : value;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
