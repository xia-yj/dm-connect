package com.dmconnect.nativeclient;

import com.dmconnect.model.ConnectionProfile;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.api.StatefulRedisConnection;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public final class RedisWorkspace implements NativeWorkspace {
    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
            "database", "tls", "ssl", "starttls", "verifypeer", "commandtimeoutms",
            "clientname", "connecttimeoutms");
    private final ConnectionProfile profile;
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> connection;
    private final int selectedDatabase;

    public RedisWorkspace(ConnectionProfile profile, char[] password) {
        this.profile = profile;
        RedisURI uri = uriFor(profile, password);
        selectedDatabase = uri.getDatabase();
        RedisClient openedClient = RedisClient.create(uri);
        int connectTimeout = integerProperty(profile.advancedProperties(), "connectTimeoutMS", 1, 600_000).orElse(10_000);
        openedClient.setOptions(ClientOptions.builder()
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(connectTimeout)).build())
                .build());
        try {
            connection = openedClient.connect(new RedisTextCodec());
            client = openedClient;
        } catch (RuntimeException exception) {
            openedClient.shutdown();
            throw exception;
        }
    }

    @Override public ConnectionProfile profile() { return profile; }
    @Override public void verify() { connection.sync().ping(); }
    public StatefulRedisConnection<String, String> connection() { return connection; }
    public int selectedDatabase() { return selectedDatabase; }
    public KeyScanCursor<String> scan(String cursor, String pattern, int count) {
        return connection.sync().scan(ScanCursor.of(cursor), ScanArgs.Builder.matches(pattern).limit(count));
    }
    @Override public void close() {
        RuntimeException failure = null;
        try {
            connection.close();
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            client.shutdown();
        } catch (RuntimeException exception) {
            if (failure == null) failure = exception;
            else failure.addSuppressed(exception);
        }
        if (failure != null) throw failure;
    }

    static RedisURI uriFor(ConnectionProfile profile, char[] password) {
        Map<String, String> properties = profile.advancedProperties();
        validateKnownProperties(properties);
        RedisURI.Builder uri = RedisURI.Builder.redis(normalizedHost(profile.host()), profile.port());
        if (!profile.username().isBlank()) uri.withAuthentication(profile.username(), password);
        else if (password.length > 0) uri.withPassword(password);

        integerProperty(properties, "database", 0, 1_000_000).ifPresent(uri::withDatabase);
        boolean ssl = booleanAlias(properties, false, "tls", "ssl");
        boolean startTls = booleanProperty(properties, "startTls", false);
        if (startTls && !ssl) throw new IllegalArgumentException("startTls=true 时必须同时启用 tls/ssl");
        uri.withSsl(ssl).withStartTls(startTls);
        property(properties, "verifyPeer").ifPresent(value -> uri.withVerifyPeer(strictBoolean("verifyPeer", value)));
        integerProperty(properties, "commandTimeoutMS", 1, 3_600_000)
                .ifPresentOrElse(value -> uri.withTimeout(Duration.ofMillis(value)),
                        () -> uri.withTimeout(Duration.ofSeconds(10)));
        property(properties, "clientName").ifPresent(value -> {
            String name = value.strip();
            if (name.isEmpty() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("clientName 必须为 1 到 128 个非控制字符");
            }
            uri.withClientName(name);
        });
        return uri.build();
    }

    public static void validateConfiguration(ConnectionProfile profile) {
        uriFor(profile, new char[0]);
    }

    private static String normalizedHost(String raw) {
        String host = raw.strip();
        boolean startsBracket = host.startsWith("[");
        boolean endsBracket = host.endsWith("]");
        if (startsBracket || endsBracket) {
            if (!startsBracket || !endsBracket || host.length() <= 2) throw new IllegalArgumentException("Redis 主机地址无效");
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)
                || host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.indexOf('#') >= 0
                || host.indexOf('@') >= 0 || host.indexOf(';') >= 0) throw new IllegalArgumentException("Redis 主机地址无效");
        if (host.chars().filter(character -> character == ':').count() == 1) throw new IllegalArgumentException("Redis 主机地址请勿包含端口");
        return host;
    }

    private static void validateKnownProperties(Map<String, String> properties) {
        properties.keySet().forEach(key -> {
            if (key == null || !ALLOWED_PROPERTIES.contains(key.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Redis 不支持高级参数：" + key);
            }
        });
    }

    private static Optional<String> property(Map<String, String> properties, String name) {
        String found = null;
        for (Map.Entry<String, String> entry : properties.entrySet()) {
            if (!entry.getKey().equalsIgnoreCase(name)) continue;
            if (found != null && !found.equals(entry.getValue())) {
                throw new IllegalArgumentException("高级参数重复且值不一致：" + name);
            }
            found = entry.getValue();
        }
        return Optional.ofNullable(found);
    }

    private static boolean booleanProperty(Map<String, String> properties, String name, boolean fallback) {
        return property(properties, name).map(value -> strictBoolean(name, value)).orElse(fallback);
    }

    private static boolean booleanAlias(Map<String, String> properties, boolean fallback, String... names) {
        Boolean found = null;
        for (String name : names) {
            Optional<String> value = property(properties, name);
            if (value.isEmpty()) continue;
            boolean parsed = strictBoolean(name, value.get());
            if (found != null && found != parsed) {
                throw new IllegalArgumentException("高级参数 " + String.join("/", names) + " 的值不一致");
            }
            found = parsed;
        }
        return found == null ? fallback : found;
    }

    private static boolean strictBoolean(String name, String raw) {
        return switch (raw.strip().toLowerCase(Locale.ROOT)) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException(name + " 只能为 true 或 false");
        };
    }

    private static Optional<Integer> integerProperty(Map<String, String> properties, String name, int minimum, int maximum) {
        Optional<String> raw = property(properties, name);
        if (raw.isEmpty()) return Optional.empty();
        try {
            int value = Integer.parseInt(raw.get().strip());
            if (value < minimum || value > maximum) throw new NumberFormatException();
            return Optional.of(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException(name + " 必须为 " + minimum + " 到 " + maximum + " 之间的整数", exception);
        }
    }
}
