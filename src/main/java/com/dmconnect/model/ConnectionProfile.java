package com.dmconnect.model;

import java.util.LinkedHashMap;
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
        String username,
        String driverId,
        Map<String, String> advancedProperties,
        boolean rememberPassword) {

    public ConnectionProfile {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(databaseType, "databaseType");
        Objects.requireNonNull(host, "host");
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
        return new ConnectionProfile(UUID.randomUUID().toString(), name, "dm", host, port, username,
                driverId, properties, rememberPassword);
    }

    public ConnectionProfile copyWithNewId(String newName) {
        return new ConnectionProfile(UUID.randomUUID().toString(), newName, databaseType, host, port,
                username, driverId, advancedProperties, rememberPassword);
    }
}
