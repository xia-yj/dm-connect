package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.database.dm.DmTableDdlBuilder;
import com.dmconnect.database.mysql.MySqlTableDdlBuilder;
import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.nativeclient.MongoWorkspace;
import com.dmconnect.nativeclient.NativeWorkspace;
import com.dmconnect.nativeclient.RedisCommandExecutor;
import com.dmconnect.nativeclient.RedisWorkspace;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.DriverDescriptor;
import com.dmconnect.model.ExecutionResult;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.PreviewFilter;
import com.dmconnect.model.StatementOutcome;
import com.dmconnect.model.TableDetails;
import com.dmconnect.model.TablePreview;
import com.dmconnect.query.CsvExporter;
import com.dmconnect.query.InsertSqlExporter;
import com.dmconnect.query.TableCsvExporter;
import com.dmconnect.query.DangerousSqlDetector;
import com.dmconnect.query.QuerySession;
import com.dmconnect.query.SqlScriptParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.bson.Document;
import io.lettuce.core.KeyScanCursor;

public final class BackendService implements AutoCloseable {
    public static final String VERSION = "2.0.0";

    private final AppContext context;
    private final ObjectMapper mapper;
    private final Map<String, BackendWorkspace> workspaces = new ConcurrentHashMap<>();
    private final Map<String, NativeWorkspace> nativeWorkspaces = new ConcurrentHashMap<>();
    private final Map<String, BackendQuery> queries = new ConcurrentHashMap<>();
    private final SqlScriptParser parser = new SqlScriptParser();
    private final DangerousSqlDetector dangerousSqlDetector = new DangerousSqlDetector();

    public BackendService(AppContext context, ObjectMapper mapper) {
        this.context = context;
        this.mapper = mapper;
    }

    public Object handle(String method, JsonNode params) throws Exception {
        JsonNode safeParams = params == null || params.isNull() ? mapper.createObjectNode() : params;
        return switch (method) {
            case "app.bootstrap" -> bootstrap();
            case "app.ping" -> Map.of("ok", true, "version", VERSION);
            case "storage.reset" -> resetLocalData();
            case "driver.list" -> driverViews();
            case "driver.import" -> importDriver(requiredText(safeParams, "path"), safeParams.path("databaseType").asText("dm"));
            case "profile.list" -> profileViews();
            case "profile.save" -> saveProfile(safeParams);
            case "profile.copy" -> copyProfile(requiredText(safeParams, "profileId"));
            case "profile.delete" -> deleteProfile(requiredText(safeParams, "profileId"));
            case "profile.test" -> testProfile(safeParams);
            case "connection.connect" -> connect(safeParams);
            case "connection.disconnect" -> disconnect(requiredText(safeParams, "profileId"));
            case "connection.schemas" -> schemas(requiredText(safeParams, "profileId"));
            case "mongo.collections" -> mongoCollections(safeParams);
            case "mongo.documents" -> mongoDocuments(safeParams);
            case "mongo.insert" -> mongoInsert(safeParams);
            case "mongo.replace" -> mongoReplace(safeParams);
            case "mongo.delete" -> mongoDelete(safeParams);
            case "redis.keys" -> redisKeys(safeParams);
            case "redis.key" -> redisKey(safeParams);
            case "redis.command" -> redisCommand(safeParams);
            case "objects.list" -> listObjects(safeParams);
            case "table.create" -> createTable(safeParams);
            case "table.alter" -> alterTable(safeParams);
            case "table.longRowStatus" -> longRowStatus(safeParams);
            case "table.setLongRow" -> setLongRow(safeParams);
            case "object.load" -> loadObject(safeParams);
            case "object.preview" -> loadPreview(safeParams);
            case "object.updateCell" -> updateObjectCell(safeParams);
            case "object.updateCells" -> updateObjectCells(safeParams);
            case "object.deleteRow" -> deleteObjectRow(safeParams);
            case "query.open" -> openQuery(requiredText(safeParams, "profileId"));
            case "query.status" -> queryStatus(requiredQuery(safeParams));
            case "query.execute" -> executeQuery(safeParams);
            case "query.cancel" -> cancelQuery(requiredQuery(safeParams));
            case "query.autoCommit" -> setAutoCommit(requiredQuery(safeParams), safeParams.path("autoCommit").asBoolean(true));
            case "query.commit" -> transaction(requiredQuery(safeParams), true);
            case "query.rollback" -> transaction(requiredQuery(safeParams), false);
            case "query.close" -> closeQuery(safeParams);
            case "history.list" -> context.vault().listHistory();
            case "history.delete" -> deleteHistory(requiredText(safeParams, "historyId"));
            case "history.clear" -> clearHistory();
            case "csv.export" -> exportCsv(safeParams);
            case "table.exportInsert" -> exportInsert(safeParams);
            case "table.exportCsv" -> exportTableCsv(safeParams);
            default -> throw new RpcException("METHOD_NOT_FOUND", "不支持的后端方法：" + method);
        };
    }

    private Map<String, Object> bootstrap() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("version", VERSION);
        result.put("profiles", profileViews());
        result.put("drivers", driverViews());
        result.put("databaseTypes", context.databaseAdapters().values().stream()
                .map(adapter -> Map.of("id", adapter.id(), "displayName", adapter.displayName(), "defaultPort", adapter.defaultPort(), "driverClass", adapter.driverClassName()))
                .toList());
        Set<String> connectedProfileIds = new java.util.LinkedHashSet<>(workspaces.keySet());
        connectedProfileIds.addAll(nativeWorkspaces.keySet());
        result.put("connectedProfileIds", List.copyOf(connectedProfileIds));
        context.vault().legacyBackup().ifPresent(path ->
                result.put("legacyVaultBackup", path.toAbsolutePath().toString()));
        return result;
    }

    private Object resetLocalData() throws Exception {
        disconnectAll();
        context.vault().resetLocalData();
        return Map.of("reset", true);
    }

    private List<Map<String, Object>> driverViews() throws Exception {
        return context.drivers().findAll().stream().map(this::driverView).toList();
    }

    private Map<String, Object> driverView(DriverDescriptor driver) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", driver.id());
        result.put("displayName", driver.displayName());
        result.put("sha256", driver.sha256());
        result.put("driverClass", driver.driverClass());
        result.put("builtIn", driver.storedPath().startsWith("classpath:"));
        result.put("databaseType", databaseTypeForDriver(driver));
        result.put("version", driver.version());
        result.put("importedAt", driver.importedAt());
        return result;
    }

    private Object importDriver(String source, String databaseType) throws Exception {
        return driverView(context.drivers().importDriver(Path.of(source), context.databaseAdapter(databaseType)));
    }

    private String databaseTypeForDriver(DriverDescriptor driver) {
        return context.databaseAdapters().values().stream()
                .filter(adapter -> adapter.driverClassName().equals(driver.driverClass()))
                .map(DatabaseAdapter::id).findFirst().orElse("unknown");
    }

    private List<Map<String, Object>> profileViews() throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ConnectionProfile profile : context.profiles().findAll()) result.add(profileView(profile));
        return List.copyOf(result);
    }

    private Map<String, Object> profileView(ConnectionProfile profile) throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("id", profile.id());
        result.put("name", profile.name());
        result.put("databaseType", profile.databaseType());
        result.put("host", profile.host());
        result.put("port", profile.port());
        result.put("database", profile.database());
        result.put("username", profile.username());
        result.put("driverId", profile.driverId());
        result.put("advancedProperties", profile.advancedProperties());
        result.put("rememberPassword", profile.rememberPassword());
        result.put("connected", workspaces.containsKey(profile.id()) || nativeWorkspaces.containsKey(profile.id()));
        result.put("hasSavedPassword", hasSavedPassword(profile.id()));
        return result;
    }

    private boolean hasSavedPassword(String profileId) throws Exception {
        if (!context.vault().isUnlocked()) return false;
        Optional<char[]> secret = context.vault().getSecret(profileId);
        if (secret.isEmpty()) return false;
        char[] value = secret.get();
        Arrays.fill(value, '\0');
        return true;
    }

    private Object saveProfile(JsonNode params) throws Exception {
        ConnectionProfile profile = profileFrom(params);
        String password = params.path("password").asText("");
        boolean databaseTypeChanged = context.profiles().findById(profile.id())
                .map(existing -> !existing.databaseType().equals(profile.databaseType()))
                .orElse(false);
        BackendWorkspace old = workspaces.remove(profile.id());
        if (old != null) old.close();
        NativeWorkspace nativeOld = nativeWorkspaces.remove(profile.id());
        if (nativeOld != null) nativeOld.close();
        closeQueries(profile.id());
        context.profiles().save(profile);
        if (!profile.rememberPassword() || databaseTypeChanged) {
            context.vault().removeSecret(profile.id());
        }
        if (profile.rememberPassword() && !password.isEmpty()) {
            char[] value = password.toCharArray();
            try {
                context.vault().putSecret(profile.id(), value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        return profileView(profile);
    }

    private Object copyProfile(String profileId) throws Exception {
        ConnectionProfile source = requireProfile(profileId);
        ConnectionProfile copy = source.copyWithNewId(source.name() + " 副本");
        context.profiles().save(copy);
        Optional<char[]> secret = context.vault().getSecret(source.id());
        if (secret.isPresent()) {
            char[] value = secret.get();
            try {
                context.vault().putSecret(copy.id(), value);
            } finally {
                Arrays.fill(value, '\0');
            }
        }
        return profileView(copy);
    }

    private Object deleteProfile(String profileId) throws Exception {
        disconnect(profileId);
        context.profiles().delete(profileId);
        context.vault().removeSecret(profileId);
        return Map.of("deleted", true);
    }

    private Object testProfile(JsonNode params) throws Exception {
        ConnectionProfile profile = profileFrom(params);
        char[] password = passwordFor(profile, params.path("password").asText(""));
        try {
            if (isNative(profile)) {
                NativeWorkspace workspace = openNativeWorkspace(profile, password);
                try {
                    workspace.verify();
                } finally {
                    workspace.close();
                }
            } else context.connections().testConnection(profile, password);
            return Map.of("success", true);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Object connect(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        ConnectionProfile profile = requireProfile(profileId);
        if (isNative(profile)) {
            String suppliedPassword = params.path("password").asText("");
            try {
                return connectNative(profileId, profile, suppliedPassword);
            } catch (Exception exception) {
                if (profile.databaseType().equals("redis") && suppliedPassword.isEmpty()
                        && redisAuthenticationFailed(exception)) {
                    throw new RpcException("PASSWORD_REQUIRED", "请输入 Redis 密码",
                            Map.of("profileId", profile.id(), "profileName", profile.name()));
                }
                throw exception;
            }
        }
        BackendWorkspace existing = workspaces.get(profileId);
        if (existing != null) {
            return Map.of("profile", profileView(profile), "schemas", schemas(profileId));
        }
        char[] password = passwordFor(profile, params.path("password").asText(""));
        BackendWorkspace workspace = new BackendWorkspace(context, profile, password);
        Arrays.fill(password, '\0');
        try (Connection connection = workspace.openConnection()) {
            List<String> schemas = adapter(workspace).metadataProvider().listSchemas(connection);
            workspaces.put(profileId, workspace);
            return Map.of("profile", profileView(profile), "schemas", schemas);
        } catch (Exception exception) {
            workspace.close();
            throw exception;
        }
    }

    private char[] passwordFor(ConnectionProfile profile, String supplied) throws Exception {
        if (supplied != null && !supplied.isEmpty()) return supplied.toCharArray();
        Optional<char[]> stored = context.vault().getSecret(profile.id());
        if (stored.isPresent()) return stored.get();
        if ((isNative(profile) && profile.username().isBlank()) || profile.databaseType().equals("sqlite")) return new char[0];
        throw new RpcException("PASSWORD_REQUIRED", "请输入数据库密码",
                Map.of("profileId", profile.id(), "profileName", profile.name()));
    }

    private Object disconnect(String profileId) {
        closeQueries(profileId);
        BackendWorkspace workspace = workspaces.remove(profileId);
        if (workspace != null) workspace.close();
        NativeWorkspace nativeWorkspace = nativeWorkspaces.remove(profileId);
        if (nativeWorkspace != null) nativeWorkspace.close();
        return Map.of("disconnected", true);
    }

    private List<String> schemas(String profileId) throws Exception {
        NativeWorkspace nativeWorkspace = nativeWorkspaces.get(profileId);
        if (nativeWorkspace instanceof MongoWorkspace mongo) return mongo.databases();
        if (nativeWorkspace instanceof RedisWorkspace redis) return List.of("db" + redis.selectedDatabase());
        BackendWorkspace workspace = requiredWorkspace(profileId);
        try (Connection connection = workspace.openConnection()) {
            return adapter(workspace).metadataProvider().listSchemas(connection);
        }
    }

    private Object connectNative(String profileId, ConnectionProfile profile, String suppliedPassword) throws Exception {
        NativeWorkspace existing = nativeWorkspaces.get(profileId);
        if (existing != null) return Map.of("profile", profileView(profile), "schemas", schemas(profileId));
        char[] password = passwordFor(profile, suppliedPassword);
        NativeWorkspace workspace = null;
        try {
            workspace = openNativeWorkspace(profile, password);
            workspace.verify();
            List<String> namespaces = workspace instanceof MongoWorkspace mongo ? mongo.databases() : List.of("db" + ((RedisWorkspace) workspace).selectedDatabase());
            NativeWorkspace concurrent = nativeWorkspaces.putIfAbsent(profileId, workspace);
            if (concurrent != null) {
                workspace.close();
            }
            workspace = null;
            return Map.of("profile", profileView(profile), "schemas", namespaces);
        } finally {
            if (workspace != null) workspace.close();
            Arrays.fill(password, '\0');
        }
    }

    private NativeWorkspace openNativeWorkspace(ConnectionProfile profile, char[] password) {
        return switch (profile.databaseType()) {
            case "mongo" -> new MongoWorkspace(profile, password);
            case "redis" -> new RedisWorkspace(profile, password);
            default -> throw new RpcException("INVALID_ARGUMENT", "不是原生客户端连接");
        };
    }

    private static boolean isNative(ConnectionProfile profile) { return profile.databaseType().equals("mongo") || profile.databaseType().equals("redis"); }

    private static boolean redisAuthenticationFailed(Throwable throwable) {
        String message = rootMessage(throwable).toUpperCase(java.util.Locale.ROOT);
        return message.contains("NOAUTH") || message.contains("WRONGPASS")
                || message.contains("AUTHENTICATION REQUIRED") || message.contains("INVALID PASSWORD");
    }

    private Object mongoCollections(JsonNode params) {
        MongoWorkspace workspace = requiredNative(requiredText(params, "profileId"), MongoWorkspace.class);
        return workspace.collections(requiredText(params, "database"));
    }

    private Object mongoDocuments(JsonNode params) throws Exception {
        MongoWorkspace workspace = requiredNative(requiredText(params, "profileId"), MongoWorkspace.class);
        String database = requiredText(params, "database");
        String collection = requiredText(params, "collection");
        Document filter = params.path("filter").asText("").isBlank() ? new Document() : Document.parse(params.path("filter").asText());
        int pageSize = Math.max(1, Math.min(200, params.path("pageSize").asInt(50)));
        int page = Math.max(1, Math.min(Integer.MAX_VALUE / pageSize, params.path("page").asInt(1)));
        List<JsonNode> documents = new ArrayList<>();
        for (Document document : workspace.documents(database, collection, filter, (page - 1) * pageSize, pageSize)) {
            // Extended JSON preserves ObjectId, Decimal128, dates and binary values instead of
            // exposing Jackson's JavaBean view of BSON implementation classes.
            documents.add(mapper.readTree(document.toJson()));
        }
        return Map.of("documents", documents, "totalRows", workspace.count(database, collection, filter), "page", page, "pageSize", pageSize);
    }

    private Object mongoInsert(JsonNode params) {
        MongoWorkspace workspace = requiredNative(requiredText(params, "profileId"), MongoWorkspace.class);
        Document document = Document.parse(requiredText(params, "document"));
        String id = workspace.insert(requiredText(params, "database"), requiredText(params, "collection"), document);
        return Map.of("inserted", true, "id", id);
    }

    private Object mongoReplace(JsonNode params) {
        MongoWorkspace workspace = requiredNative(requiredText(params, "profileId"), MongoWorkspace.class);
        long updated = workspace.replace(requiredText(params, "database"), requiredText(params, "collection"), requiredText(params, "id"), Document.parse(requiredText(params, "document")));
        return Map.of("updated", updated);
    }

    private Object mongoDelete(JsonNode params) {
        MongoWorkspace workspace = requiredNative(requiredText(params, "profileId"), MongoWorkspace.class);
        long deleted = workspace.delete(requiredText(params, "database"), requiredText(params, "collection"), requiredText(params, "id"));
        return Map.of("deleted", deleted);
    }

    private Object redisKeys(JsonNode params) {
        RedisWorkspace workspace = requiredNative(requiredText(params, "profileId"), RedisWorkspace.class);
        String pattern = params.path("pattern").asText("*");
        if (pattern.isBlank()) pattern = "*";
        if (pattern.length() > 4_096 || pattern.indexOf('\0') >= 0) {
            throw new RpcException("INVALID_ARGUMENT", "Redis SCAN 匹配模式无效");
        }
        String requestedCursor = params.path("cursor").asText("0").strip();
        if (!requestedCursor.matches("[0-9]{1,20}")) {
            throw new RpcException("INVALID_ARGUMENT", "Redis SCAN 游标必须为非负整数");
        }
        int requestedCount = params.has("count") ? params.path("count").asInt(200) : params.path("pageSize").asInt(200);
        int count = Math.max(1, Math.min(1_000, requestedCount));
        KeyScanCursor<String> cursor = workspace.scan(requestedCursor, pattern, count);
        return Map.of("keys", cursor.getKeys(), "cursor", cursor.getCursor(), "finished", cursor.isFinished());
    }

    private Object redisKey(JsonNode params) {
        RedisWorkspace workspace = requiredNative(requiredText(params, "profileId"), RedisWorkspace.class);
        String key = requiredText(params, "key");
        var commands = workspace.connection().sync();
        String type = commands.type(key);
        Object value = switch (type) {
            case "string" -> {
                long length = commands.strlen(key);
                String content = commands.getrange(key, 0, 65_535);
                yield Map.of("content", content == null ? "(nil)" : content,
                        "length", length, "truncated", length > 65_536);
            }
            case "hash" -> {
                var cursor = commands.hscan(key, io.lettuce.core.ScanArgs.Builder.limit(200));
                Map<String, String> entries = new LinkedHashMap<>();
                cursor.getMap().entrySet().stream().limit(200)
                        .forEach(entry -> entries.put(entry.getKey(), entry.getValue()));
                yield Map.of("entries", entries, "length", commands.hlen(key),
                        "truncated", !cursor.isFinished() || cursor.getMap().size() > entries.size());
            }
            case "list" -> {
                long length = commands.llen(key);
                yield Map.of("items", commands.lrange(key, 0, 199), "length", length,
                        "truncated", length > 200);
            }
            case "set" -> {
                var cursor = commands.sscan(key, io.lettuce.core.ScanArgs.Builder.limit(200));
                List<String> members = cursor.getValues().stream().limit(200).toList();
                yield Map.of("members", members, "length", commands.scard(key),
                        "truncated", !cursor.isFinished() || cursor.getValues().size() > members.size());
            }
            case "zset" -> {
                long length = commands.zcard(key);
                List<Map<String, Object>> members = commands.zrangeWithScores(key, 0, 199).stream()
                        .map(item -> Map.<String, Object>of("value", item.getValue(), "score", item.getScore()))
                        .toList();
                yield Map.of("members", members, "length", length, "truncated", length > 200);
            }
            case "none" -> "(nil)";
            default -> "该键类型暂不支持可视化读取";
        };
        return Map.of("key", key, "type", type, "ttl", commands.ttl(key), "value", value == null ? "(nil)" : value);
    }

    private Object redisCommand(JsonNode params) {
        RedisWorkspace workspace = requiredNative(requiredText(params, "profileId"), RedisWorkspace.class);
        RedisCommandExecutor.ParsedCommand command;
        try {
            command = RedisCommandExecutor.parse(requiredText(params, "command"));
        } catch (IllegalArgumentException exception) {
            throw new RpcException("INVALID_ARGUMENT", exception.getMessage());
        }
        if (command.blocked()) {
            String message = switch (command.risk()) {
                case BLOCKING -> "该命令可能无限阻塞连接，命令台不允许执行";
                case LARGE_RESULT -> "该命令可能返回无界大结果，请使用有截断保护的键查看器";
                case UNKNOWN -> "该命令不在安全允许列表中，命令台不允许执行";
                default -> "该命令会改变或破坏当前 Redis 会话，命令台不允许执行";
            };
            throw new RpcException("UNSUPPORTED_COMMAND", message,
                    Map.of("command", command.operation(), "risk", command.risk().wireName(), "dangerous", true));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("command", command.operation());
        response.put("risk", command.risk().wireName());
        response.put("dangerous", command.dangerous());
        boolean confirmed = params.path("confirmed").asBoolean(false);
        if (command.dangerous() && !confirmed) {
            response.put("executed", false);
            response.put("requiresConfirmation", true);
            return response;
        }
        Object result;
        try {
            result = RedisCommandExecutor.execute(workspace.connection(), command);
        } catch (IllegalArgumentException exception) {
            throw new RpcException("UNSUPPORTED_COMMAND", exception.getMessage());
        }
        response.put("executed", true);
        response.put("requiresConfirmation", false);
        response.put("result", result == null ? "(nil)" : result);
        return response;
    }

    private <T extends NativeWorkspace> T requiredNative(String profileId, Class<T> type) {
        NativeWorkspace workspace = nativeWorkspaces.get(profileId);
        if (!type.isInstance(workspace)) throw new RpcException("NOT_CONNECTED", "请先连接对应的数据库");
        return type.cast(workspace);
    }

    private Object listObjects(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        String schema = requiredText(params, "schema");
        DatabaseObjectKind kind = DatabaseObjectKind.valueOf(requiredText(params, "kind"));
        try (Connection connection = workspace.openConnection()) {
            return adapter(workspace).metadataProvider().listObjects(connection, schema, kind);
        }
    }

    private Object createTable(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        DmTableDdlBuilder.Definition definition = tableDefinition(params);
        BackendWorkspace workspace = requiredWorkspace(profileId);
        DatabaseAdapter adapter = adapter(workspace);
        if (!adapter.id().equals("dm") && !adapter.id().equals("mysql")) throw new RpcException("UNSUPPORTED_OPERATION", "当前数据库首版请通过 SQL 工作台创建表");
        String sql;
        List<String> comments;
        if (adapter.id().equals("mysql")) {
            MySqlTableDdlBuilder ddl = new MySqlTableDdlBuilder(adapter);
            sql = ddl.create(definition);
            comments = ddl.createComments(definition);
        } else {
            DmTableDdlBuilder ddl = new DmTableDdlBuilder(adapter);
            sql = ddl.create(definition);
            comments = ddl.createComments(definition);
        }
        try (Connection connection = workspace.openConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            for (String comment : comments) statement.execute(comment);
        }
        return Map.of("created", true, "sql", sql);
    }

    private Object alterTable(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        DmTableDdlBuilder.Definition original = tableDefinition(params.path("original"));
        DmTableDdlBuilder.Definition target = tableDefinition(params.path("target"));
        BackendWorkspace workspace = requiredWorkspace(profileId);
        DatabaseAdapter adapter = adapter(workspace);
        if (!adapter.id().equals("dm") && !adapter.id().equals("mysql")) throw new RpcException("UNSUPPORTED_OPERATION", "当前数据库首版请通过 SQL 工作台修改表");
        List<String> statements = adapter.id().equals("mysql")
                ? new MySqlTableDdlBuilder(adapter).alter(original, target)
                : new DmTableDdlBuilder(adapter).alter(original, target);
        try (Connection connection = workspace.openConnection(); var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
        return Map.of("altered", true, "statements", statements);
    }

    private Object longRowStatus(JsonNode params) throws Exception {
        DatabaseObject table = tableObject(params);
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        if (!adapter(workspace).id().equals("dm")) return Map.of("enabled", false, "supported", false);
        try (Connection connection = workspace.openConnection()) {
            DatabaseAdapter adapter = adapter(workspace);
            String ddl = adapter.ddlProvider().getDdl(connection, table);
            return Map.of("enabled", ddl != null && ddl.toUpperCase(java.util.Locale.ROOT).matches("(?s).*\\bUSING\\s+LONG\\s+ROW\\b.*"), "supported", true);
        }
    }

    private Object setLongRow(JsonNode params) throws Exception {
        DatabaseObject table = tableObject(params);
        boolean enabled = params.path("enabled").asBoolean();
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseAdapter adapter = adapter(workspace);
        if (!adapter.id().equals("dm")) throw new RpcException("UNSUPPORTED_OPERATION", "超长记录设置仅适用于达梦数据库");
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        try (Connection connection = workspace.openConnection(); Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + source + (enabled ? " ENABLE USING LONG ROW" : " DISABLE USING LONG ROW"));
        }
        return Map.of("enabled", enabled);
    }

    private static DatabaseObject tableObject(JsonNode params) {
        return new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"), DatabaseObjectKind.TABLE, "");
    }

    private DmTableDdlBuilder.Definition tableDefinition(JsonNode node) {
        if (node == null || !node.isObject()) throw new RpcException("INVALID_ARGUMENT", "表定义无效");
        JsonNode items = node.path("columns");
        if (!items.isArray()) throw new RpcException("INVALID_ARGUMENT", "字段定义无效");
        List<DmTableDdlBuilder.Column> columns = new ArrayList<>();
        for (JsonNode item : items) {
            columns.add(new DmTableDdlBuilder.Column(
                    nullableText(item, "originalName"), requiredText(item, "name"), requiredText(item, "type"),
                    nullableInteger(item, "length"), nullableInteger(item, "scale"), item.path("nullable").asBoolean(true),
                    item.path("primaryKey").asBoolean(false), item.path("autoIncrement").asBoolean(false),
                    nullableText(item, "defaultExpression"), nullableText(item, "onUpdateExpression"), nullableText(item, "remark")));
        }
        return new DmTableDdlBuilder.Definition(requiredText(node, "schema"), requiredText(node, "name"), columns,
                nullableText(node, "primaryKeyName"));
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static Integer nullableInteger(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || value.asText().isBlank()) return null;
        if (!value.canConvertToInt()) throw new RpcException("INVALID_ARGUMENT", field + "必须是整数");
        return value.asInt();
    }

    private Object loadObject(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject object = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"),
                DatabaseObjectKind.valueOf(requiredText(params, "kind")), params.path("remarks").asText(""));
        TableDetails details = null;
        TablePreview preview = null;
        String ddl = null;
        String detailsError = "";
        String previewError = "";
        String ddlError = "";
        try (Connection connection = workspace.openConnection()) {
            if (object.kind() == DatabaseObjectKind.TABLE) {
                try {
                    details = adapter(workspace).metadataProvider().describeTable(connection, object);
                } catch (Exception exception) {
                    detailsError = "读取表结构失败：" + rootMessage(exception);
                }
                try {
                    preview = adapter(workspace).metadataProvider().previewTable(connection, object, 0, 100);
                } catch (Exception exception) {
                    previewError = "读取数据预览失败：" + rootMessage(exception);
                }
            }
            try {
                ddl = adapter(workspace).ddlProvider().getDdl(connection, object);
            } catch (Exception exception) {
                ddlError = "读取 DDL 失败：" + rootMessage(exception) + "。当前用户可能没有该对象的元数据权限。";
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("object", object);
        result.put("details", details);
        result.put("detailsError", detailsError);
        result.put("preview", preview == null ? null : previewView(preview, 1, 100));
        result.put("previewError", previewError);
        result.put("ddl", ddl);
        result.put("ddlError", ddlError);
        return result;
    }

    private Object loadPreview(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject object = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"),
                DatabaseObjectKind.valueOf(requiredText(params, "kind")), params.path("remarks").asText(""));
        if (object.kind() != DatabaseObjectKind.TABLE) {
            throw new RpcException("INVALID_OBJECT", "只有数据表支持数据预览");
        }
        int pageSize = Math.max(1, Math.min(500, params.path("pageSize").asInt(100)));
        int page = Math.max(1, Math.min(1_000_000, params.path("page").asInt(1)));
        PreviewFilter filter = previewFilter(params);
        try (Connection connection = workspace.openConnection()) {
            TablePreview preview = adapter(workspace).metadataProvider()
                    .previewTable(connection, object, (page - 1) * pageSize, pageSize, filter);
            long lastPage = Math.max(1, (preview.totalRows() + pageSize - 1) / pageSize);
            int safePage = (int) Math.min(page, lastPage);
            if (safePage != page) {
                preview = adapter(workspace).metadataProvider()
                        .previewTable(connection, object, (safePage - 1) * pageSize, pageSize, filter);
            }
            return previewView(preview, safePage, pageSize);
        }
    }

    private static PreviewFilter previewFilter(JsonNode params) {
        JsonNode filters = params.path("filters");
        if (filters.isArray() && !filters.isEmpty()) {
            if (filters.size() > 10) throw new RpcException("INVALID_ARGUMENT", "筛选条件最多 10 个");
            List<PreviewFilter.Condition> conditions = new ArrayList<>();
            for (JsonNode item : filters) conditions.add(previewCondition(item));
            return new PreviewFilter(conditions);
        }
        String column = params.path("filterColumn").asText("").strip();
        if (column.isEmpty()) return null;
        return new PreviewFilter(List.of(previewCondition(params)));
    }

    private static PreviewFilter.Condition previewCondition(JsonNode params) {
        String column = params.path("column").asText(params.path("filterColumn").asText("")).strip();
        if (column.length() > 128) throw new RpcException("INVALID_ARGUMENT", "筛选列名过长");
        if (column.isEmpty()) throw new RpcException("INVALID_ARGUMENT", "筛选列名不能为空");
        String operator = params.path("operator").asText(params.path("filterOperator").asText("=")).strip().toUpperCase(java.util.Locale.ROOT);
        if (!operator.equals("=") && !operator.equals("LIKE")) throw new RpcException("INVALID_ARGUMENT", "筛选仅支持 = 或 LIKE");
        String value = params.has("value") ? params.path("value").asText(null) : params.path("filterValue").asText(null);
        if (value == null) throw new RpcException("INVALID_ARGUMENT", "筛选条件缺少值");
        return new PreviewFilter.Condition(column, operator, value);
    }

    private Object updateObjectCell(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject object = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"),
                DatabaseObjectKind.valueOf(requiredText(params, "kind")), params.path("remarks").asText(""));
        try (Connection connection = workspace.openConnection()) {
            return Map.of("updated", updateObjectCell(connection, adapter(workspace), object, requiredText(params, "column"),
                    params.get("value"), params.path("keyValues")));
        }
    }

    private Object updateObjectCells(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject object = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"),
                DatabaseObjectKind.valueOf(requiredText(params, "kind")), params.path("remarks").asText(""));
        JsonNode changes = params.path("changes");
        if (!changes.isArray() || changes.isEmpty()) throw new RpcException("INVALID_REQUEST", "没有要保存的修改");
        try (Connection connection = workspace.openConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int updated = 0;
                for (JsonNode change : changes) {
                    updated += updateObjectCell(connection, adapter(workspace), object, requiredText(change, "column"),
                            change.get("value"), change.path("keyValues"));
                }
                connection.commit();
                return Map.of("updated", updated);
            } catch (Exception exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        }
    }

    private Object deleteObjectRow(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject object = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"),
                DatabaseObjectKind.valueOf(requiredText(params, "kind")), params.path("remarks").asText(""));
        JsonNode keyValues = params.path("keyValues");
        JsonNode rowValues = params.path("rowValues");
        if (!keyValues.isObject() && rowValues.isObject()) keyValues = rowValues;
        if (!keyValues.isObject()) throw new RpcException("INVALID_REQUEST", "缺少用于定位记录的主键值");
        if (object.kind() != DatabaseObjectKind.TABLE) throw new RpcException("INVALID_OBJECT", "只有数据表支持删除数据");
        try (Connection connection = workspace.openConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            DatabaseMetaData metadata = connection.getMetaData();
            List<String> primaryKeys = new ArrayList<>();
            try (var rs = metadata.getPrimaryKeys(adapter(workspace).metadataCatalog(object.schema()), adapter(workspace).metadataSchema(object.schema()), object.name())) {
                while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"));
            }
            if (primaryKeys.isEmpty()) throw new RpcException("PRIMARY_KEY_REQUIRED", "该表没有主键，无法安全地删除数据");
            String source = adapter(workspace).quoteIdentifier(object.schema()) + "." + adapter(workspace).quoteIdentifier(object.name());
            String where = primaryKeys.stream().map(key -> adapter(workspace).quoteIdentifier(key) + " = ?")
                    .collect(java.util.stream.Collectors.joining(" AND "));
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + source + " WHERE " + where)) {
                for (int index = 0; index < primaryKeys.size(); index++) {
                    String key = primaryKeys.get(index);
                    JsonNode keyValue = keyValues.get(key);
                    if (keyValue == null) keyValue = keyValues.properties().stream().filter(entry -> entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue).findFirst().orElse(null);
                    if (keyValue == null || keyValue.isNull()) throw new RpcException("INVALID_REQUEST", "缺少主键列“" + key + "”的值");
                    bindJsonValue(statement, index + 1, keyValue);
                }
                int deleted = statement.executeUpdate();
                if (deleted != 1) throw new RpcException("DELETE_CONFLICT", "未能精确删除一条记录，数据可能已被其他操作修改");
                if (!autoCommit) connection.commit();
                return Map.of("deleted", deleted);
            }
        }
    }

    private int updateObjectCell(Connection connection, DatabaseAdapter adapter, DatabaseObject object, String requestedColumn,
                                 JsonNode value, JsonNode keyValues) throws Exception {
        if (object.kind() != DatabaseObjectKind.TABLE) {
            throw new RpcException("INVALID_OBJECT", "只有数据表支持直接编辑");
        }
        if (!keyValues.isObject()) throw new RpcException("INVALID_REQUEST", "缺少用于定位记录的主键值");

        DatabaseMetaData metadata = connection.getMetaData();
        String column = null;
        int columnType = Types.OTHER;
        boolean autoIncrement = false;
        boolean generated = false;
        try (var rs = metadata.getColumns(adapter.metadataCatalog(object.schema()), adapter.metadataSchema(object.schema()), object.name(), "%")) {
            while (rs.next()) {
                String candidate = rs.getString("COLUMN_NAME");
                if (candidate == null || !candidate.equalsIgnoreCase(requestedColumn)) continue;
                column = candidate;
                columnType = rs.getInt("DATA_TYPE");
                try {
                    autoIncrement = "YES".equalsIgnoreCase(rs.getString("IS_AUTOINCREMENT"));
                } catch (java.sql.SQLException ignored) {
                    // Optional in older JDBC drivers.
                }
                try {
                    generated = "YES".equalsIgnoreCase(rs.getString("IS_GENERATEDCOLUMN"));
                } catch (java.sql.SQLException ignored) {
                    // Optional in older JDBC drivers.
                }
                break;
            }
        }
        if (column == null) throw new RpcException("INVALID_COLUMN", "找不到要更新的列：" + requestedColumn);
        if (autoIncrement || generated) {
            throw new RpcException("READ_ONLY_COLUMN", "自增列或生成列不能直接编辑");
        }
        if (!directlyEditableJdbcType(columnType)) {
            throw new RpcException("READ_ONLY_COLUMN", "大对象、二进制或复杂类型不能在数据预览中直接编辑，请使用 SQL 工作台");
        }
        List<String> primaryKeys = new ArrayList<>();
        try (var rs = metadata.getPrimaryKeys(adapter.metadataCatalog(object.schema()), adapter.metadataSchema(object.schema()), object.name())) {
            while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"));
        }
        if (primaryKeys.isEmpty()) throw new RpcException("PRIMARY_KEY_REQUIRED", "该表没有主键，无法安全地直接编辑数据");
        if (primaryKeys.stream().anyMatch(key -> key.equalsIgnoreCase(requestedColumn))) {
            throw new RpcException("READ_ONLY_COLUMN", "主键列不能在数据预览中直接编辑");
        }

        String source = adapter.quoteIdentifier(object.schema()) + "." + adapter.quoteIdentifier(object.name());
        String where = primaryKeys.stream().map(key -> adapter.quoteIdentifier(key) + " = ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "UPDATE " + source + " SET " + adapter.quoteIdentifier(column) + " = ? WHERE " + where;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindJsonValue(statement, 1, value);
            for (int index = 0; index < primaryKeys.size(); index++) {
                String key = primaryKeys.get(index);
                JsonNode keyValue = keyValues.get(key);
                if (keyValue == null) {
                    keyValue = keyValues.properties().stream()
                            .filter(entry -> entry.getKey().equalsIgnoreCase(key)).map(Map.Entry::getValue)
                            .findFirst().orElse(null);
                }
                if (keyValue == null || keyValue.isNull()) {
                    throw new RpcException("INVALID_REQUEST", "缺少主键列“" + key + "”的值");
                }
                bindJsonValue(statement, index + 2, keyValue);
            }
            int updated = statement.executeUpdate();
            if (updated != 1) throw new RpcException("UPDATE_CONFLICT", "未能精确更新一条记录，数据可能已被其他操作修改");
            return updated;
        }
    }

    private static boolean directlyEditableJdbcType(int type) {
        return switch (type) {
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB,
                    Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR,
                    Types.SQLXML, Types.ARRAY, Types.STRUCT, Types.JAVA_OBJECT, Types.OTHER -> false;
            default -> true;
        };
    }

    private Object openQuery(String profileId) throws Exception {
        ConnectionProfile profile = requireProfile(profileId);
        if (isNative(profile)) throw new RpcException("UNSUPPORTED_OPERATION", "当前连接使用原生工作台，不支持 SQL 会话");
        BackendWorkspace workspace = requiredWorkspace(profileId);
        BackendQuery query = new BackendQuery(profileId, workspace, workspace.openSession());
        queries.put(query.id(), query);
        return Map.of("sessionId", query.id(), "profileId", profileId, "autoCommit", true,
                "pendingTransaction", false);
    }

    private Object queryStatus(BackendQuery query) throws Exception {
        return Map.of("sessionId", query.id(), "profileId", query.profileId(),
                "autoCommit", query.session().isAutoCommit(),
                "pendingTransaction", query.session().hasPendingTransaction());
    }

    private Object executeQuery(JsonNode params) throws Exception {
        BackendQuery query = requiredQuery(params);
        String sql = requiredText(params, "sql");
        String mode = params.path("mode").asText("SCRIPT");
        boolean mysqlBackslashEscapes = true;
        boolean mysqlAnsiQuotes = false;
        if ("mysql".equalsIgnoreCase(query.databaseType())) {
            try {
                Set<String> sqlModes = query.session().mysqlSqlModes();
                mysqlBackslashEscapes = !sqlModes.contains("NO_BACKSLASH_ESCAPES");
                mysqlAnsiQuotes = sqlModes.contains("ANSI_QUOTES");
            } catch (java.sql.SQLException ignored) {
                // Fall back to MySQL's default mode if a compatible server hides sql_mode.
            }
        }
        final boolean backslashEscapes = mysqlBackslashEscapes;
        final boolean ansiQuotes = mysqlAnsiQuotes;
        List<String> statements = switch (mode) {
            case "SELECTION" -> parser.split(params.path("selection").asText(""), query.databaseType(), backslashEscapes, ansiQuotes);
            case "CURRENT_STATEMENT" -> parser.currentStatement(sql, params.path("caretOffset").asInt(0), query.databaseType(), backslashEscapes, ansiQuotes)
                    .map(statement -> List.of(statement.sql())).orElseGet(List::of);
            case "SCRIPT" -> parser.split(sql, query.databaseType(), backslashEscapes, ansiQuotes);
            default -> throw new RpcException("INVALID_ARGUMENT", "未知的 SQL 执行模式：" + mode);
        };
        if (statements.isEmpty()) throw new RpcException("NO_SQL", "没有可执行的 SQL");
        List<String> dangerous = dangerousSqlDetector.findDangerous(statements);
        if (!dangerous.isEmpty() && !params.path("allowDangerous").asBoolean(false)) {
            throw new RpcException("DANGEROUS_SQL", "检测到 DROP 或 TRUNCATE 语句", dangerous);
        }
        // 结果集始终固定在 100 行以内，不能由客户端请求扩大，防止大查询耗尽桌面端内存。
        int maxRows = QuerySession.MAX_RESULT_ROWS;
        ExecutionResult execution = query.session().execute(statements, maxRows);
        String historyWarning = "";
        try {
            ConnectionProfile profile = requireProfile(query.profileId());
            context.vault().addHistory(profile.id(), profile.name(), execution.success(), execution.durationMillis(),
                    String.join(";\n\n", statements));
        } catch (Exception exception) {
            historyWarning = rootMessage(exception);
        }
        query.clearResults();
        Map<String, Object> response = executionView(query, execution);
        response.put("historyWarning", historyWarning);
        response.put("autoCommit", query.session().isAutoCommit());
        response.put("pendingTransaction", query.session().hasPendingTransaction());
        return response;
    }

    private Map<String, Object> executionView(BackendQuery query, ExecutionResult execution) {
        List<Map<String, Object>> outcomes = new ArrayList<>();
        for (StatementOutcome outcome : execution.outcomes()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("statementIndex", outcome.statementIndex());
            item.put("resultIndex", outcome.resultIndex());
            item.put("updateCount", outcome.updateCount());
            if (outcome.hasTable()) {
                item.put("resultId", query.store(outcome.table()));
                item.put("table", tableView(outcome.table()));
            } else {
                item.put("resultId", null);
                item.put("table", null);
            }
            outcomes.add(item);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("outcomes", outcomes);
        result.put("success", execution.success());
        result.put("errorMessage", execution.errorMessage());
        result.put("sqlState", execution.sqlState());
        result.put("vendorCode", execution.vendorCode());
        result.put("durationMillis", execution.durationMillis());
        result.put("executedStatements", execution.executedStatements());
        return result;
    }

    private Object cancelQuery(BackendQuery query) throws Exception {
        query.session().cancel();
        return Map.of("cancelled", true);
    }

    private Object setAutoCommit(BackendQuery query, boolean autoCommit) throws Exception {
        query.session().setAutoCommit(autoCommit);
        return queryStatus(query);
    }

    private Object transaction(BackendQuery query, boolean commit) throws Exception {
        if (commit) query.session().commit();
        else query.session().rollback();
        return queryStatus(query);
    }

    private Object closeQuery(JsonNode params) throws Exception {
        BackendQuery query = requiredQuery(params);
        String transactionAction = params.path("transactionAction").asText("");
        if (query.session().hasPendingTransaction()) {
            if ("COMMIT".equals(transactionAction)) query.session().commit();
            else if ("ROLLBACK".equals(transactionAction)) query.session().rollback();
            else throw new RpcException("PENDING_TRANSACTION", "此 SQL 标签存在未提交事务");
        }
        queries.remove(query.id());
        query.close();
        return Map.of("closed", true);
    }

    private Object deleteHistory(String historyId) throws Exception {
        context.vault().deleteHistory(historyId);
        return Map.of("deleted", true);
    }

    private Object clearHistory() throws Exception {
        context.vault().clearHistory();
        return Map.of("cleared", true);
    }

    private Object exportCsv(JsonNode params) throws Exception {
        BackendQuery query = requiredQuery(params);
        String resultId = requiredText(params, "resultId");
        Path target = Path.of(requiredText(params, "path"));
        CsvExporter.write(query.result(resultId), target);
        return Map.of("path", target.toAbsolutePath().toString());
    }

    private Object exportInsert(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject table = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"), DatabaseObjectKind.TABLE, "");
        String scope = params.path("scope").asText("CURRENT_PAGE");
        int pageSize = Math.max(1, Math.min(500, params.path("pageSize").asInt(100)));
        int page = Math.max(1, params.path("page").asInt(1));
        int offset = "CURRENT_PAGE".equals(scope) ? (page - 1) * pageSize : 0;
        int maxRows = "CURRENT_PAGE".equals(scope) ? pageSize : 0;
        if (!"CURRENT_PAGE".equals(scope) && !"ALL".equals(scope)) throw new RpcException("INVALID_ARGUMENT", "未知导出范围");
        Path target = Path.of(requiredText(params, "path"));
        try (Connection connection = workspace.openConnection()) {
            long rows = InsertSqlExporter.write(connection, adapter(workspace), table, previewFilter(params), offset, maxRows, target);
            return Map.of("path", target.toAbsolutePath().toString(), "rows", rows);
        }
    }

    private Object exportTableCsv(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        DatabaseObject table = new DatabaseObject(requiredText(params, "schema"), requiredText(params, "name"), DatabaseObjectKind.TABLE, "");
        String scope = params.path("scope").asText("CURRENT_PAGE");
        int pageSize = Math.max(1, Math.min(500, params.path("pageSize").asInt(100)));
        int page = Math.max(1, params.path("page").asInt(1));
        if (!"CURRENT_PAGE".equals(scope) && !"ALL".equals(scope)) throw new RpcException("INVALID_ARGUMENT", "未知导出范围");
        try (Connection connection = workspace.openConnection()) {
            long rows = TableCsvExporter.write(connection, adapter(workspace), table, previewFilter(params), "CURRENT_PAGE".equals(scope) ? (page - 1) * pageSize : 0, "CURRENT_PAGE".equals(scope) ? pageSize : 0, Path.of(requiredText(params, "path")));
            return Map.of("rows", rows);
        }
    }

    private Map<String, Object> tableView(ResultTable table) {
        List<List<Object>> rows = new ArrayList<>(table.rows().size());
        for (List<Object> row : table.rows()) {
            List<Object> values = new ArrayList<>(row.size());
            for (Object value : row) values.add(jsonValue(value));
            rows.add(values);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", table.columns());
        result.put("rows", rows);
        result.put("truncated", table.truncated());
        return result;
    }

    private Map<String, Object> previewView(TablePreview preview, int page, int pageSize) {
        Map<String, Object> result = tableView(preview.table());
        result.put("totalRows", preview.totalRows());
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }

    private void bindJsonValue(PreparedStatement statement, int index, JsonNode value) throws Exception {
        if (value == null || value.isNull()) {
            statement.setNull(index, Types.NULL);
        } else if (value.isBoolean()) {
            statement.setBoolean(index, value.booleanValue());
        } else if (value.isIntegralNumber()) {
            if (value.canConvertToLong()) statement.setLong(index, value.longValue());
            else statement.setBigDecimal(index, new BigDecimal(value.bigIntegerValue()));
        } else if (value.isFloatingPointNumber()) {
            statement.setBigDecimal(index, value.decimalValue());
        } else {
            statement.setString(index, value.asText());
        }
    }

    private Object jsonValue(Object value) {
        if (value == null || value instanceof String || value instanceof Boolean) return value;
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof BigInteger integer) return integer.toString();
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Float
                || value instanceof Double) return value;
        if (value instanceof Long number) {
            long raw = number;
            return Math.abs(raw) <= 9_007_199_254_740_991L ? raw : Long.toString(raw);
        }
        if (value instanceof Date date) return date.toLocalDate().toString();
        if (value instanceof Time time) return time.toLocalTime().toString();
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
        if (value instanceof TemporalAccessor) return value.toString();
        return String.valueOf(value);
    }

    private ConnectionProfile profileFrom(JsonNode params) throws Exception {
        String id = params.path("id").asText("").strip();
        String databaseType = params.path("databaseType").asText("dm").strip().toLowerCase(java.util.Locale.ROOT);
        DatabaseAdapter adapter = context.databaseAdapter(databaseType);
        String name = requiredText(params, "name").strip();
        String host = requiredText(params, "host").strip();
        int port = params.path("port").asInt(adapter.defaultPort());
        String database = params.path("database").asText("").strip();
        boolean nativeClient = databaseType.equals("mongo") || databaseType.equals("redis");
        String username = params.path("username").asText("").strip();
        if (!nativeClient && !databaseType.equals("sqlite") && username.isBlank()) throw new RpcException("INVALID_ARGUMENT", "缺少参数：username");
        String driverId = nativeClient ? "builtin:" + databaseType : requiredText(params, "driverId");
        Optional<DriverDescriptor> selectedDriver = nativeClient ? Optional.empty() : context.drivers().findById(driverId);
        if (selectedDriver.isPresent() && !selectedDriver.get().driverClass().equals(adapter.driverClassName())) {
            throw new RpcException("INVALID_ARGUMENT", "所选 JDBC 驱动不适用于" + adapter.displayName());
        }
        Map<String, String> properties = new LinkedHashMap<>();
        JsonNode advanced = params.path("advancedProperties");
        if (advanced.isObject()) advanced.fields().forEachRemaining(entry -> {
            if (DatabaseAdapter.isSensitiveProperty(entry.getKey())) {
                throw new RpcException("INVALID_ARGUMENT", "用户名、密码或其他凭据不能写入高级连接参数");
            }
            if (databaseType.equals("mysql") && (entry.getKey().equalsIgnoreCase("databaseTerm")
                    || entry.getKey().equalsIgnoreCase("useAffectedRows"))) {
                throw new RpcException("INVALID_ARGUMENT", "MySQL 高级参数不允许覆盖 " + entry.getKey());
            }
            properties.put(entry.getKey(), entry.getValue().asText(""));
        });
        // Validate host, port, database path and URL encoding before persisting the profile.
        adapter.buildJdbcUrl(host, port, database, properties);
        boolean remember = params.path("rememberPassword").asBoolean(true);
        ConnectionProfile profile = id.isEmpty()
                ? ConnectionProfile.create(databaseType, name, host, port, database, username, driverId, properties, remember)
                : new ConnectionProfile(id, name, databaseType, host, port, database, username, driverId, properties, remember);
        if (databaseType.equals("mongo")) MongoWorkspace.validateConfiguration(profile);
        if (databaseType.equals("redis")) RedisWorkspace.validateConfiguration(profile);
        return profile;
    }

    private ConnectionProfile requireProfile(String profileId) throws Exception {
        return context.profiles().findById(profileId)
                .orElseThrow(() -> new RpcException("PROFILE_NOT_FOUND", "连接配置不存在或已被删除"));
    }

    private BackendWorkspace requiredWorkspace(String profileId) {
        BackendWorkspace workspace = workspaces.get(profileId);
        if (workspace == null) throw new RpcException("NOT_CONNECTED", "请先连接数据库");
        return workspace;
    }

    private DatabaseAdapter adapter(BackendWorkspace workspace) {
        return context.databaseAdapter(workspace.profile().databaseType());
    }

    private BackendQuery requiredQuery(JsonNode params) {
        String sessionId = requiredText(params, "sessionId");
        BackendQuery query = queries.get(sessionId);
        if (query == null) throw new RpcException("SESSION_NOT_FOUND", "SQL 会话已经关闭");
        return query;
    }

    private static String requiredText(JsonNode params, String name) {
        String value = params.path(name).asText("");
        if (value.isBlank()) throw new RpcException("INVALID_ARGUMENT", "缺少参数：" + name);
        return value;
    }

    private void closeQueries(String profileId) {
        List<BackendQuery> matches = queries.values().stream()
                .filter(query -> query.profileId().equals(profileId)).toList();
        matches.forEach(query -> {
            queries.remove(query.id());
            query.close();
        });
    }

    private void disconnectAll() {
        queries.values().forEach(BackendQuery::close);
        queries.clear();
        workspaces.values().forEach(BackendWorkspace::close);
        workspaces.clear();
        nativeWorkspaces.values().forEach(workspace -> {
            try {
                workspace.close();
            } catch (RuntimeException ignored) {
                // Continue releasing the remaining clients during reset/application shutdown.
            }
        });
        nativeWorkspaces.clear();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    @Override
    public void close() {
        disconnectAll();
    }
}
