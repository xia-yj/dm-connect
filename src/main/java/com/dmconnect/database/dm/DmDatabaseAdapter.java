package com.dmconnect.database.dm;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.database.spi.MetadataProvider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Collectors;

public final class DmDatabaseAdapter implements DatabaseAdapter {
    public static final String DRIVER_CLASS = "dm.jdbc.driver.DmDriver";
    private final DmMetadataProvider metadataProvider = new DmMetadataProvider(this);
    private final DmDdlProvider ddlProvider = new DmDdlProvider();

    @Override
    public String id() {
        return "dm";
    }

    @Override
    public String displayName() {
        return "达梦数据库";
    }

    @Override
    public int defaultPort() {
        return 5236;
    }

    @Override
    public String driverClassName() {
        return DRIVER_CLASS;
    }

    @Override
    public String buildJdbcUrl(String host, int port, Map<String, String> advancedProperties) {
        if (host == null || host.isBlank()) throw new IllegalArgumentException("主机不能为空");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("端口无效");
        String base = "jdbc:dm://" + host.strip() + ":" + port;
        if (advancedProperties == null || advancedProperties.isEmpty()) return base;

        String query = advancedProperties.entrySet().stream()
                .filter(entry -> entry.getKey() != null && !entry.getKey().isBlank())
                .filter(entry -> !entry.getKey().equalsIgnoreCase("user"))
                .filter(entry -> !entry.getKey().equalsIgnoreCase("password"))
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> encode(entry.getKey().strip()) + "=" + encode(entry.getValue() == null ? "" : entry.getValue()))
                .collect(Collectors.joining("&"));
        return query.isEmpty() ? base : base + "?" + query;
    }

    @Override
    public String quoteIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty()) throw new IllegalArgumentException("标识符不能为空");
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    @Override
    public MetadataProvider metadataProvider() {
        return metadataProvider;
    }

    @Override
    public DdlProvider ddlProvider() {
        return ddlProvider;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
