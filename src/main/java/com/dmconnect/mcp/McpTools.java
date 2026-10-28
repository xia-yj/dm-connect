package com.dmconnect.mcp;

import com.dmconnect.AppContext;
import com.dmconnect.backend.BackendErrorFormatter;
import com.dmconnect.backend.BackendService;
import com.dmconnect.backend.RpcException;
import com.dmconnect.model.ColumnMetadata;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.nativeclient.RedisCommandExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 把 BackendService 的 RPC 包装成 MCP 工具。默认只读：SQL 只允许查询语句，并在只读事务里执行后回滚。
 */
public final class McpTools {
    /** 权限级别：写入模式拦截危险 SQL 和 Redis 管理命令，完全权限不拦截。 */
    public enum Access {
        READ_ONLY, WRITE, FULL;

        boolean canWrite() { return this != READ_ONLY; }
    }

    private static final int MAX_CELL_CHARS = 2_000;
    private static final List<String> OBJECT_KINDS = Arrays.stream(DatabaseObjectKind.values()).map(Enum::name).toList();

    private final AppContext context;
    private final BackendService service;
    private final ObjectMapper mapper;
    private final ObjectWriter compactWriter;
    private final Access access;
    private final boolean allowWrite;

    public McpTools(AppContext context, BackendService service, ObjectMapper mapper, Access access) {
        this.context = context;
        this.service = service;
        this.mapper = mapper;
        this.compactWriter = mapper.writer().without(SerializationFeature.INDENT_OUTPUT);
        this.access = access;
        this.allowWrite = access.canWrite();
    }

    List<Map<String, Object>> definitions() {
        Map<String, Object> connection = string("连接名称或连接 ID，见 list_connections");
        List<Map<String, Object>> tools = new ArrayList<>();
        tools.add(tool("list_connections", "列出数据库连接工具里保存的全部连接（不含密码），以及当前权限级别。",
                true, Map.of()));
        tools.add(tool("list_schemas", "连接数据库并列出模式/库。关系型数据库返回模式，MongoDB 返回数据库，Redis 返回当前逻辑库。",
                true, Map.of("connection", connection), "connection"));
        tools.add(tool("list_objects", "列出关系型数据库某个模式下的对象名称和注释。",
                true, props(
                        "connection", connection,
                        "schema", string("模式名，见 list_schemas"),
                        "kind", enumOf("对象类型，默认 TABLE", OBJECT_KINDS),
                        "keyword", string("可选，按名称或注释模糊过滤（不区分大小写）")),
                "connection", "schema"));
        tools.add(tool("describe_object", "查看对象结构：表返回列、约束、索引和 DDL，其他对象返回 DDL。",
                true, props(
                        "connection", connection,
                        "schema", string("模式名"),
                        "name", string("对象名"),
                        "kind", enumOf("对象类型，默认 TABLE", OBJECT_KINDS)),
                "connection", "schema", "name"));
        tools.add(tool("preview_table", "分页预览表数据，可按列做 = 或 LIKE 筛选。",
                true, props(
                        "connection", connection,
                        "schema", string("模式名"),
                        "name", string("表名"),
                        "page", integer("页码，从 1 开始，默认 1"),
                        "pageSize", integer("每页行数，默认 20，最大 100"),
                        "filters", Map.of("type", "array", "description", "可选筛选条件，最多 10 个，条件之间为 AND",
                                "items", Map.of("type", "object", "properties", props(
                                                "column", string("列名"),
                                                "operator", enumOf("比较方式", List.of("=", "LIKE")),
                                                "value", string("比较值")),
                                        "required", List.of("column", "value")))),
                "connection", "schema", "name"));
        tools.add(tool("execute_sql", switch (access) {
                    case FULL -> "执行任意 SQL（多条用分号分隔，自动提交）。当前为完全权限：DROP、TRUNCATE、不带 WHERE 的 DELETE/UPDATE 等也会直接执行，执行前请确认。";
                    case WRITE -> "执行 SQL（多条用分号分隔，自动提交）。DROP、TRUNCATE、ALTER、GRANT、REVOKE 和不带 WHERE 的 DELETE/UPDATE 会被拒绝，这类语句请在桌面端执行。";
                    case READ_ONLY -> "执行只读 SQL 查询（SELECT、WITH、SHOW、DESC、EXPLAIN），多条用分号分隔。当前为只读模式：写语句会被拒绝，查询在事务里执行后回滚。";
                },
                !allowWrite, props(
                        "connection", connection,
                        "sql", string("要执行的 SQL"),
                        "maxRows", integer("每个结果集最多返回行数，按 100（默认）、1000、5000 三档向上取整")),
                "connection", "sql"));
        tools.add(tool("mongo_list_collections", "列出 MongoDB 某个数据库下的集合。",
                true, props("connection", connection, "database", string("数据库名，见 list_schemas")),
                "connection", "database"));
        tools.add(tool("mongo_find", "分页查询 MongoDB 文档，结果为 Extended JSON。",
                true, props(
                        "connection", connection,
                        "database", string("数据库名"),
                        "collection", string("集合名"),
                        "filter", string("可选，Extended JSON 格式的查询条件，如 {\"status\": \"active\"}"),
                        "page", integer("页码，从 1 开始，默认 1"),
                        "pageSize", integer("每页文档数，默认 20，最大 50")),
                "connection", "database", "collection"));
        tools.add(tool("redis_scan", "用 SCAN 分页列出 Redis 键。",
                true, props(
                        "connection", connection,
                        "pattern", string("匹配模式，默认 *"),
                        "cursor", string("游标，首次为 0，之后传上次返回的 cursor"),
                        "count", integer("每次扫描数量提示，默认 200，最大 1000")),
                "connection"));
        tools.add(tool("redis_get", "读取 Redis 键的类型、TTL 和值（大值会截断）。",
                true, props("connection", connection, "key", string("键名")),
                "connection", "key"));
        if (allowWrite) addWriteTools(tools, connection);
        return tools;
    }

    private void addWriteTools(List<Map<String, Object>> tools, Map<String, Object> connection) {
        Map<String, Object> database = string("数据库名");
        Map<String, Object> collection = string("集合名");
        Map<String, Object> id = string("文档 _id：ObjectId 的 24 位十六进制字符串，或字符串/数字形式的 _id");
        tools.add(tool("mongo_insert", "向 MongoDB 集合插入一个文档。",
                false, props("connection", connection, "database", database, "collection", collection,
                        "document", string("Extended JSON 格式的文档")),
                "connection", "database", "collection", "document"));
        tools.add(tool("mongo_replace", "按 _id 整体替换 MongoDB 文档。",
                false, props("connection", connection, "database", database, "collection", collection, "id", id,
                        "document", string("替换后的完整文档，Extended JSON 格式")),
                "connection", "database", "collection", "id", "document"));
        tools.add(tool("mongo_delete", "按 _id 删除一个 MongoDB 文档。",
                false, props("connection", connection, "database", database, "collection", collection, "id", id),
                "connection", "database", "collection", "id"));
        tools.add(tool("redis_command", access == Access.FULL
                        ? "执行一条 Redis 命令，如 SET k v、DEL k、HSET h f v，含 FLUSHDB、CONFIG 等管理命令。阻塞命令、KEYS 和改变会话的命令（SELECT、MULTI 等）仍不允许。"
                        : "执行一条 Redis 命令，如 SET k v、DEL k、HSET h f v。FLUSHDB、FLUSHALL、CONFIG、EVAL 等管理命令需要完全权限；阻塞命令、KEYS 和改变会话的命令不允许。",
                false, props("connection", connection, "command", string("完整命令，参数含空格时用引号包住")),
                "connection", "command"));
    }

    Map<String, Object> call(JsonNode params) {
        String name = params.path("name").asText("");
        JsonNode arguments = params.path("arguments").isObject() ? params.path("arguments") : mapper.createObjectNode();
        try {
            Object result = switch (name) {
                case "list_connections" -> listConnections();
                case "list_schemas" -> listSchemas(arguments);
                case "list_objects" -> listObjects(arguments);
                case "describe_object" -> describeObject(arguments);
                case "preview_table" -> previewTable(arguments);
                case "execute_sql" -> executeSql(arguments);
                case "mongo_list_collections" -> service.handle("mongo.collections", connected(arguments)
                        .put("database", required(arguments, "database")));
                case "mongo_find" -> mongoFind(arguments);
                case "redis_scan" -> redisScan(arguments);
                case "redis_get" -> service.handle("redis.key", connected(arguments).put("key", required(arguments, "key")));
                case "mongo_insert" -> mongoWrite("mongo.insert", arguments, false);
                case "mongo_replace" -> mongoWrite("mongo.replace", arguments, true);
                case "mongo_delete" -> mongoWrite("mongo.delete", arguments, true);
                case "redis_command" -> redisCommand(arguments);
                default -> throw new McpServer.McpError(-32602, "未知工具：" + name);
            };
            return textResult(compactWriter.writeValueAsString(result), false);
        } catch (McpServer.McpError error) {
            throw error;
        } catch (RpcException exception) {
            String message = BackendErrorFormatter.format(exception);
            if (exception.data() instanceof List<?> statements && !statements.isEmpty()) {
                message += "\n被拒绝的语句：\n" + String.join("\n", statements.stream().map(String::valueOf).toList());
            }
            return textResult(message, true);
        } catch (Exception exception) {
            return textResult(BackendErrorFormatter.format(exception), true);
        }
    }

    private Object listConnections() throws Exception {
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Object item : (List<?>) service.handle("profile.list", null)) {
            Map<?, ?> profile = (Map<?, ?>) item;
            Map<String, Object> view = new LinkedHashMap<>();
            for (String key : List.of("id", "name", "databaseType", "host", "port", "database", "username", "connected", "hasSavedPassword")) {
                view.put(key, profile.get(key));
            }
            connections.add(view);
        }
        return Map.of("readOnly", !allowWrite, "access", access.name(), "connections", connections);
    }

    private Object listSchemas(JsonNode arguments) throws Exception {
        Map<?, ?> profile = profile(arguments);
        Map<?, ?> connected = (Map<?, ?>) connect((String) profile.get("id"));
        return Map.of("connection", profile.get("name"), "databaseType", profile.get("databaseType"),
                "schemas", connected.get("schemas"));
    }

    private Object listObjects(JsonNode arguments) throws Exception {
        ObjectNode params = connected(arguments)
                .put("schema", required(arguments, "schema"))
                .put("kind", kind(arguments));
        String keyword = arguments.path("keyword").asText("").strip().toLowerCase(Locale.ROOT);
        List<Map<String, Object>> objects = new ArrayList<>();
        for (Object item : (List<?>) service.handle("objects.list", params)) {
            DatabaseObject object = (DatabaseObject) item;
            if (!keyword.isEmpty() && !object.name().toLowerCase(Locale.ROOT).contains(keyword)
                    && !object.remarks().toLowerCase(Locale.ROOT).contains(keyword)) continue;
            objects.add(object.remarks().isBlank()
                    ? Map.of("name", object.name())
                    : Map.of("name", object.name(), "remarks", object.remarks()));
        }
        return Map.of("count", objects.size(), "objects", objects);
    }

    private Object describeObject(JsonNode arguments) throws Exception {
        ObjectNode params = connected(arguments)
                .put("schema", required(arguments, "schema"))
                .put("name", required(arguments, "name"))
                .put("kind", kind(arguments));
        Map<String, Object> result = new LinkedHashMap<>(castMap((Map<?, ?>) service.handle("object.load", params)));
        // 数据预览交给 preview_table，避免每次看结构都带回 100 行数据。
        result.remove("preview");
        result.remove("previewError");
        result.values().removeIf(value -> value == null || "".equals(value));
        return result;
    }

    private Object previewTable(JsonNode arguments) throws Exception {
        ObjectNode params = connected(arguments)
                .put("schema", required(arguments, "schema"))
                .put("name", required(arguments, "name"))
                .put("kind", DatabaseObjectKind.TABLE.name())
                .put("page", Math.max(1, arguments.path("page").asInt(1)))
                .put("pageSize", Math.max(1, Math.min(100, arguments.path("pageSize").asInt(20))));
        if (arguments.path("filters").isArray()) params.set("filters", arguments.path("filters"));
        return compactTable((Map<?, ?>) service.handle("object.preview", params));
    }

    private Object executeSql(JsonNode arguments) throws Exception {
        String profileId = connected(arguments).path("profileId").asText();
        String sql = required(arguments, "sql");
        String sessionId = (String) ((Map<?, ?>) service.handle("query.open",
                mapper.createObjectNode().put("profileId", profileId))).get("sessionId");
        try {
            if (!allowWrite) {
                service.handle("query.autoCommit", mapper.createObjectNode().put("sessionId", sessionId).put("autoCommit", false));
            }
            Map<?, ?> execution = (Map<?, ?>) service.handle("query.execute", mapper.createObjectNode()
                    .put("sessionId", sessionId)
                    .put("sql", sql)
                    .put("mode", "SCRIPT")
                    .put("maxRows", arguments.path("maxRows").asInt(100))
                    .put("readOnly", !allowWrite)
                    .put("allowDangerous", access == Access.FULL));
            List<Map<String, Object>> results = new ArrayList<>();
            for (Object item : (List<?>) execution.get("outcomes")) {
                Map<?, ?> outcome = (Map<?, ?>) item;
                Map<String, Object> view = new LinkedHashMap<>();
                view.put("statement", outcome.get("statementIndex"));
                if (outcome.get("table") instanceof Map<?, ?> table) view.putAll(compactTable(table));
                else view.put("updateCount", outcome.get("updateCount"));
                results.add(view);
            }
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", execution.get("success"));
            if (!Boolean.TRUE.equals(execution.get("success"))) result.put("errorMessage", execution.get("errorMessage"));
            result.put("executedStatements", execution.get("executedStatements"));
            result.put("durationMillis", execution.get("durationMillis"));
            result.put("results", results);
            if (!allowWrite) result.put("rolledBack", true);
            return result;
        } finally {
            // 只读模式下统一回滚，兜住带副作用的函数调用等检测不到的写入。
            try {
                service.handle("query.close", mapper.createObjectNode().put("sessionId", sessionId)
                        .put("transactionAction", allowWrite ? "COMMIT" : "ROLLBACK"));
            } catch (Exception exception) {
                exception.printStackTrace(System.err);
            }
        }
    }

    private Object mongoFind(JsonNode arguments) throws Exception {
        return service.handle("mongo.documents", connected(arguments)
                .put("database", required(arguments, "database"))
                .put("collection", required(arguments, "collection"))
                .put("filter", arguments.path("filter").asText(""))
                .put("page", Math.max(1, arguments.path("page").asInt(1)))
                .put("pageSize", Math.max(1, Math.min(50, arguments.path("pageSize").asInt(20)))));
    }

    private Object mongoWrite(String method, JsonNode arguments, boolean byId) throws Exception {
        requireWrite();
        ObjectNode params = connected(arguments)
                .put("database", required(arguments, "database"))
                .put("collection", required(arguments, "collection"));
        if (byId) params.put("id", required(arguments, "id"));
        if (!"mongo.delete".equals(method)) params.put("document", required(arguments, "document"));
        return service.handle(method, params);
    }

    private Object redisCommand(JsonNode arguments) throws Exception {
        requireWrite();
        String command = required(arguments, "command");
        RedisCommandExecutor.ParsedCommand parsed;
        try {
            parsed = RedisCommandExecutor.parse(command);
        } catch (IllegalArgumentException exception) {
            throw new RpcException("INVALID_ARGUMENT", exception.getMessage());
        }
        // 写入模式只放行普通读写命令，FLUSHDB、CONFIG 这类管理命令要完全权限。
        if (access != Access.FULL && parsed.risk() == RedisCommandExecutor.Risk.ADMIN) {
            throw new RpcException("ACCESS_DENIED", parsed.operation() + " 是 Redis 管理命令，需要以完全权限启动 MCP（--full-access）");
        }
        return service.handle("redis.command", connected(arguments).put("command", command).put("confirmed", true));
    }

    private void requireWrite() {
        if (!allowWrite) throw new RpcException("ACCESS_DENIED", "当前为只读模式，不允许写入");
    }

    private Object redisScan(JsonNode arguments) throws Exception {
        return service.handle("redis.keys", connected(arguments)
                .put("pattern", arguments.path("pattern").asText("*"))
                .put("cursor", arguments.path("cursor").asText("0"))
                .put("count", arguments.path("count").asInt(200)));
    }

    /** 解析连接并按需建立连接，返回带 profileId 的参数对象。 */
    private ObjectNode connected(JsonNode arguments) throws Exception {
        Map<?, ?> profile = profile(arguments);
        String profileId = (String) profile.get("id");
        if (!Boolean.TRUE.equals(profile.get("connected"))) connect(profileId);
        return mapper.createObjectNode().put("profileId", profileId);
    }

    private Map<?, ?> profile(JsonNode arguments) throws Exception {
        String key = required(arguments, "connection").strip();
        List<Map<?, ?>> profiles = ((List<?>) service.handle("profile.list", null)).stream()
                .<Map<?, ?>>map(item -> (Map<?, ?>) item).toList();
        for (Map<?, ?> profile : profiles) if (key.equals(profile.get("id"))) return profile;
        for (Map<?, ?> profile : profiles) if (key.equals(profile.get("name"))) return profile;
        List<Map<?, ?>> similar = profiles.stream()
                .filter(profile -> String.valueOf(profile.get("name")).equalsIgnoreCase(key)).toList();
        if (similar.size() == 1) return similar.get(0);
        List<String> names = profiles.stream().map(profile -> String.valueOf(profile.get("name"))).toList();
        throw new RpcException("CONNECTION_NOT_FOUND", "找不到连接：" + key + "。可用连接：" + String.join("、", names));
    }

    private Object connect(String profileId) throws Exception {
        ObjectNode params = mapper.createObjectNode().put("profileId", profileId);
        try {
            return service.handle("connection.connect", params);
        } catch (RpcException exception) {
            if (!"PASSWORD_REQUIRED".equals(exception.code())) throw exception;
        }
        // 本进程的 vault 是启动时读入的，桌面端之后保存的密码要重新加载才能看到。
        synchronized (context.vault()) {
            context.vault().close();
            context.vault().open();
        }
        try {
            return service.handle("connection.connect", params);
        } catch (RpcException exception) {
            if (!"PASSWORD_REQUIRED".equals(exception.code())) throw exception;
            throw new RpcException("PASSWORD_REQUIRED",
                    "该连接没有保存密码。请在数据库连接工具里编辑该连接，勾选“记住密码”并保存后重试。");
        }
    }

    private Map<String, Object> compactTable(Map<?, ?> table) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("columns", ((List<?>) table.get("columns")).stream().map(item -> {
            ColumnMetadata column = (ColumnMetadata) item;
            return column.label() == null || column.label().isBlank() ? column.name() : column.label();
        }).toList());
        List<List<Object>> rows = new ArrayList<>();
        for (Object row : (List<?>) table.get("rows")) {
            List<Object> values = new ArrayList<>();
            for (Object value : (List<?>) row) {
                values.add(value instanceof String text && text.length() > MAX_CELL_CHARS
                        ? text.substring(0, MAX_CELL_CHARS) + "…（共 " + text.length() + " 字符，已截断）"
                        : value);
            }
            rows.add(values);
        }
        result.put("rows", rows);
        result.put("truncated", table.get("truncated"));
        for (String key : List.of("totalRows", "page", "pageSize")) {
            if (table.containsKey(key)) result.put(key, table.get(key));
        }
        return result;
    }

    private static String kind(JsonNode arguments) {
        String kind = arguments.path("kind").asText("TABLE").strip().toUpperCase(Locale.ROOT);
        if (!OBJECT_KINDS.contains(kind)) {
            throw new RpcException("INVALID_ARGUMENT", "kind 只能是 " + String.join("、", OBJECT_KINDS));
        }
        return kind;
    }

    private static String required(JsonNode arguments, String field) {
        String value = arguments.path(field).asText("");
        if (value.isBlank()) throw new RpcException("INVALID_ARGUMENT", "缺少参数：" + field);
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> value) {
        return (Map<String, Object>) value;
    }

    private static Map<String, Object> textResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    private static Map<String, Object> tool(String name, String description, boolean readOnly,
                                            Map<String, Object> properties, String... required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required.length > 0) schema.put("required", List.of(required));
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("description", description);
        tool.put("inputSchema", schema);
        tool.put("annotations", Map.of("readOnlyHint", readOnly, "destructiveHint", !readOnly, "openWorldHint", false));
        return tool;
    }

    private static Map<String, Object> props(Object... entries) {
        Map<String, Object> properties = new LinkedHashMap<>();
        for (int index = 0; index < entries.length; index += 2) properties.put((String) entries[index], entries[index + 1]);
        return properties;
    }

    private static Map<String, Object> string(String description) {
        return Map.of("type", "string", "description", description);
    }

    private static Map<String, Object> integer(String description) {
        return Map.of("type", "integer", "description", description);
    }

    private static Map<String, Object> enumOf(String description, List<String> values) {
        return Map.of("type", "string", "description", description, "enum", values);
    }
}
