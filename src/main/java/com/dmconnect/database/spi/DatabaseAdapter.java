package com.dmconnect.database.spi;

import java.util.Map;

public interface DatabaseAdapter {
    String id();

    String displayName();

    int defaultPort();

    String driverClassName();

    String buildJdbcUrl(String host, int port, Map<String, String> advancedProperties);

    String quoteIdentifier(String identifier);

    MetadataProvider metadataProvider();

    DdlProvider ddlProvider();
}
