package com.dmconnect.model;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** 不包含明文密码的连接配置。 */
public record ConnectionProfile(
        String id,
        String name,
        String databaseType,
        String host,
        int port,
        String database,
        String username,
        String driverId,
        Map<String, String> advancedProperties,
        boolean rememberPassword) {

    public ConnectionProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        // Profiles written before multi-database support do not contain this field.
        // Treat them as DM profiles so an upgrade never makes existing connections unreadable.
        databaseType = databaseType == null || databaseType.isBlank()
                ? "dm"
                : databaseType.strip().toLowerCase(Locale.ROOT);
        Objects.requireNonNull(host, "host");
        database = database == null ? "" : database.strip();
        Objects.requireNonNull(username, "username");
        Objects.requireNonNull(driverId, "driverId");
        if (name.isBlank()) throw new IllegalArgumentException("连接名称不能为空");
        if (host.isBlank()) throw new IllegalArgumentException("主机不能为空");
        if (port < 1 || port > 65_535) throw new IllegalArgumentException("端口必须在 1 到 65535 之间");
        advancedProperties = advancedProperties == null
                ? Map.of()
                : Map.copyOf(new LinkedHashMap<>(advancedProperties));
    }

    public static ConnectionProfile create(String name, String host, int port, String username,
                                           String driverId, Map<String, String> properties,
                                           boolean rememberPassword) {
        return create("dm", name, host, port, "", username, driverId, properties, rememberPassword);
    }

    public static ConnectionProfile create(String databaseType, String name, String host, int port, String database, String username,
                                           String driverId, Map<String, String> properties,
                                           boolean rememberPassword) {
        return new ConnectionProfile(UUID.randomUUID().toString(), name, databaseType, host, port, database, username,
                driverId, properties, rememberPassword);
    }

    public ConnectionProfile copyWithNewId(String newName) {
        return new ConnectionProfile(UUID.randomUUID().toString(), newName, databaseType, host, port, database,
                username, driverId, advancedProperties, rememberPassword);
    }
}
