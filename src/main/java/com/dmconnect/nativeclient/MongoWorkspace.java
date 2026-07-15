package com.dmconnect.nativeclient;

import com.dmconnect.model.ConnectionProfile;
import com.mongodb.AuthenticationMechanism;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCommandException;
import com.mongodb.MongoCredential;
import com.mongodb.ReadPreference;
import com.mongodb.ServerAddress;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.model.Sorts;
import com.mongodb.connection.ClusterConnectionMode;
import org.bson.Document;
import org.bson.json.JsonMode;
import org.bson.json.JsonWriterSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class MongoWorkspace implements NativeWorkspace {
    private static final JsonWriterSettings CANONICAL_EXTENDED_JSON = JsonWriterSettings.builder()
            .outputMode(JsonMode.EXTENDED)
            .build();
    private static final Set<String> ALLOWED_PROPERTIES = Set.of(
            "tls", "ssl", "tlsallowinvalidhostnames", "sslinvalidhostnameallowed",
            "serverselectiontimeoutms", "replicaset", "directconnection", "connecttimeoutms",
            "sockettimeoutms", "authsource", "authmechanism", "applicationname",
            "readpreference", "retryreads", "retrywrites");
    private final ConnectionProfile profile;
    private final MongoClient client;

    public MongoWorkspace(ConnectionProfile profile, char[] password) {
        this.profile = profile;
        client = MongoClients.create(settingsFor(profile, password));
    }

    @Override public ConnectionProfile profile() { return profile; }

    public List<String> databases() {
        List<String> names = new ArrayList<>();
        try {
            client.listDatabaseNames().into(names);
        } catch (MongoCommandException exception) {
            if (exception.getErrorCode() != 13) throw exception;
            names.add(defaultDatabase());
        }
        return names;
    }

    @Override public void verify() {
        client.getDatabase(defaultDatabase()).runCommand(new Document("ping", 1));
    }

    public List<String> collections(String database) {
        List<String> names = new ArrayList<>();
        client.getDatabase(database).listCollectionNames().into(names);
        return names;
    }

    public List<Document> documents(String database, String collection, Document filter, int skip, int limit) {
        return client.getDatabase(database).getCollection(collection).find(filter)
                .sort(Sorts.ascending("_id")).skip(skip).limit(limit).into(new ArrayList<>());
    }

    public long count(String database, String collection, Document filter) {
        return client.getDatabase(database).getCollection(collection).countDocuments(filter);
    }

    public String insert(String database, String collection, Document document) {
        client.getDatabase(database).getCollection(collection).insertOne(document);
        return displayId(document);
    }

    public long replace(String database, String collection, String id, Document document) {
        document.remove("_id");
        return client.getDatabase(database).getCollection(collection).replaceOne(idFilter(id), document).getModifiedCount();
    }

    public long delete(String database, String collection, String id) {
        return client.getDatabase(database).getCollection(collection).deleteOne(idFilter(id)).getDeletedCount();
    }

    @Override public void close() { client.close(); }

    private String defaultDatabase() { return profile.database().isBlank() ? "admin" : profile.database(); }

    static MongoClientSettings settingsFor(ConnectionProfile profile, char[] password) {
        Map<String, String> properties = profile.advancedProperties();
        validateKnownProperties(properties);
        boolean tls = booleanAlias(properties, false, "tls", "ssl");
        boolean allowInvalidHostnames = booleanAlias(properties, false,
                "tlsAllowInvalidHostnames", "sslInvalidHostNameAllowed");
        if (allowInvalidHostnames && !tls) {
            throw new IllegalArgumentException("tlsAllowInvalidHostnames=true 时必须同时启用 tls/ssl");
        }

        MongoClientSettings.Builder settings = MongoClientSettings.builder();
        String host = normalizedHost(profile.host());
        int serverSelectionTimeout = integerProperty(properties, "serverSelectionTimeoutMS", 1, 600_000).orElse(10_000);
        Optional<String> replicaSet = property(properties, "replicaSet");
        boolean directConnection = booleanProperty(properties, "directConnection", false);
        settings.applyToClusterSettings(cluster -> {
            cluster.hosts(List.of(new ServerAddress(host, profile.port())));
            cluster.serverSelectionTimeout(serverSelectionTimeout, TimeUnit.MILLISECONDS);
            replicaSet.filter(value -> !value.isBlank()).ifPresent(cluster::requiredReplicaSetName);
            if (directConnection) cluster.mode(ClusterConnectionMode.SINGLE);
        });

        int connectTimeout = integerProperty(properties, "connectTimeoutMS", 1, 600_000).orElse(10_000);
        int socketTimeout = integerProperty(properties, "socketTimeoutMS", 1, 3_600_000).orElse(30_000);
        settings.applyToSocketSettings(socket -> {
            socket.connectTimeout(connectTimeout, TimeUnit.MILLISECONDS);
            socket.readTimeout(socketTimeout, TimeUnit.MILLISECONDS);
        });
        settings.applyToSslSettings(ssl -> ssl.enabled(tls).invalidHostNameAllowed(allowInvalidHostnames));

        if (!profile.username().isBlank()) {
            String authSource = property(properties, "authSource")
                    .map(String::strip)
                    .filter(value -> !value.isEmpty())
                    .orElse(profile.database().isBlank() ? "admin" : profile.database());
            MongoCredential credential = MongoCredential.createCredential(profile.username(), authSource, password);
            Optional<String> mechanism = property(properties, "authMechanism");
            if (mechanism.isPresent() && !mechanism.get().isBlank()) {
                AuthenticationMechanism parsed = AuthenticationMechanism.fromMechanismName(mechanism.get().strip());
                if (parsed != AuthenticationMechanism.SCRAM_SHA_1 && parsed != AuthenticationMechanism.SCRAM_SHA_256) {
                    throw new IllegalArgumentException("当前用户名/密码连接仅支持 SCRAM-SHA-1 或 SCRAM-SHA-256");
                }
                credential = credential.withMechanism(parsed);
            }
            settings.credential(credential);
        } else if (password.length > 0) {
            throw new IllegalArgumentException("MongoDB 提供密码时必须同时提供用户名");
        }

        property(properties, "applicationName").ifPresent(value -> {
            String name = value.strip();
            if (name.isEmpty() || name.length() > 128 || name.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("applicationName 必须为 1 到 128 个非控制字符");
            }
            settings.applicationName(name);
        });
        property(properties, "readPreference").ifPresent(value -> {
            try {
                settings.readPreference(ReadPreference.valueOf(value.strip()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("readPreference 无效：" + value, exception);
            }
        });
        property(properties, "retryReads").ifPresent(value -> settings.retryReads(strictBoolean("retryReads", value)));
        property(properties, "retryWrites").ifPresent(value -> settings.retryWrites(strictBoolean("retryWrites", value)));
        return settings.build();
    }

    private static String normalizedHost(String raw) {
        String host = raw.strip();
        boolean startsBracket = host.startsWith("[");
        boolean endsBracket = host.endsWith("]");
        if (startsBracket || endsBracket) {
            if (!startsBracket || !endsBracket || host.length() <= 2) throw new IllegalArgumentException("MongoDB 主机地址无效");
            host = host.substring(1, host.length() - 1);
        }
        if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)
                || host.indexOf('/') >= 0 || host.indexOf('?') >= 0 || host.indexOf('#') >= 0
                || host.indexOf('@') >= 0 || host.indexOf(';') >= 0) throw new IllegalArgumentException("MongoDB 主机地址无效");
        if (host.chars().filter(character -> character == ':').count() == 1) throw new IllegalArgumentException("MongoDB 主机地址请勿包含端口");
        return host;
    }

    public static void validateConfiguration(ConnectionProfile profile) {
        settingsFor(profile, new char[0]);
    }

    private static void validateKnownProperties(Map<String, String> properties) {
        properties.keySet().forEach(key -> {
            if (key == null || !ALLOWED_PROPERTIES.contains(key.strip().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("MongoDB 不支持高级参数：" + key);
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

    private static Document idFilter(String id) { return new Document("_id", parseId(id)); }

    static Object parseId(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("MongoDB _id 不能为空");
        String value = raw.strip();
        boolean jsonValue = value.startsWith("{") || value.startsWith("[") || value.startsWith("\"")
                || value.equals("true") || value.equals("false") || value.equals("null")
                || value.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
        if (!jsonValue) return value;
        try {
            return Document.parse("{\"_id\":" + value + "}").get("_id");
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("MongoDB _id 不是有效的 Extended JSON", exception);
        }
    }

    static String displayId(Document document) {
        String wrapped = new Document("_id", document.get("_id")).toJson(CANONICAL_EXTENDED_JSON);
        int valueStart = wrapped.indexOf(':') + 1;
        return wrapped.substring(valueStart, wrapped.length() - 1).strip();
    }
}
