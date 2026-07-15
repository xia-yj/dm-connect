package com.dmconnect.database.spi;

import java.util.Map;
import java.sql.PreparedStatement;
import java.sql.SQLException;

public interface DatabaseAdapter {
    static boolean isSensitiveProperty(String key) {
        if (key == null) return false;
        String normalized = key.strip().toLowerCase(java.util.Locale.ROOT);
        String compact = normalized.replaceAll("[^a-z0-9]", "");
        return normalized.equals("user") || normalized.equals("username")
                || normalized.contains("password") || normalized.equals("passwd") || normalized.equals("pwd")
                || normalized.contains("token") || normalized.contains("secret")
                || normalized.contains("credential") || normalized.contains("passphrase")
                || compact.contains("privatekey") || compact.contains("apikey");
    }

    String id();

    String displayName();

    int defaultPort();

    String driverClassName();

    String buildJdbcUrl(String host, int port, Map<String, String> advancedProperties);

    default String buildJdbcUrl(String host, int port, String database, Map<String, String> advancedProperties) {
        return buildJdbcUrl(host, port, advancedProperties);
    }

    /** Advanced values that must be supplied through Driver.connect rather than embedded in a URL. */
    default Map<String, String> connectionProperties(Map<String, String> advancedProperties) {
        return Map.of();
    }

    String quoteIdentifier(String identifier);

    default String stringLiteral(String value) {
        if (value == null) return "NULL";
        return "'" + value.replace("'", "''") + "'";
    }

    /** JDBC metadata catalog argument for the application's schema/database namespace. */
    default String metadataCatalog(String namespace) {
        return null;
    }

    /** JDBC metadata schema argument for the application's schema/database namespace. */
    default String metadataSchema(String namespace) {
        return namespace;
    }

    /** Allows a driver to opt into cursor/streaming result retrieval for large exports. */
    default void configureStreaming(PreparedStatement statement) throws SQLException {
        // Most JDBC drivers stream or fetch in bounded batches by default.
    }

    MetadataProvider metadataProvider();

    DdlProvider ddlProvider();
}
