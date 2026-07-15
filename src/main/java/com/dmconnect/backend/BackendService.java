package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.database.dm.DmTableDdlBuilder;
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
import java.util.concurrent.ConcurrentHashMap;

public final class BackendService implements AutoCloseable {
    public static final String VERSION = "2.0.0";

    private final AppContext context;
    private final ObjectMapper mapper;
    private final Map<String, BackendWorkspace> workspaces = new ConcurrentHashMap<>();
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
            case "driver.import" -> importDriver(requiredText(safeParams, "path"));
            case "profile.list" -> profileViews();
            case "profile.save" -> saveProfile(safeParams);
            case "profile.copy" -> copyProfile(requiredText(safeParams, "profileId"));
            case "profile.delete" -> deleteProfile(requiredText(safeParams, "profileId"));
            case "profile.test" -> testProfile(safeParams);
            case "connection.connect" -> connect(safeParams);
            case "connection.disconnect" -> disconnect(requiredText(safeParams, "profileId"));
            case "connection.schemas" -> schemas(requiredText(safeParams, "profileId"));
            case "objects.list" -> listObjects(safeParams);
            case "table.create" -> createTable(safeParams);
            case "table.alter" -> alterTable(safeParams);
            case "table.longRowStatus" -> longRowStatus(safeParams);
            case "table.setLongRow" -> setLongRow(safeParams);
            case "object.load" -> loadObject(safeParams);
            case "object.preview" -> loadPreview(safeParams);
            case "object.updateCell" -> updateObjectCell(safeParams);
            case "object.updateCells" -> updateObjectCells(safeParams);
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
        result.put("connectedProfileIds", List.copyOf(workspaces.keySet()));
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
        result.put("version", driver.version());
        result.put("importedAt", driver.importedAt());
        return result;
    }

    private Object importDriver(String source) throws Exception {
        return driverView(context.drivers().importDriver(Path.of(source)));
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
        result.put("username", profile.username());
        result.put("driverId", profile.driverId());
        result.put("advancedProperties", profile.advancedProperties());
        result.put("rememberPassword", profile.rememberPassword());
        result.put("connected", workspaces.containsKey(profile.id()));
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
        BackendWorkspace old = workspaces.remove(profile.id());
        if (old != null) old.close();
        closeQueries(profile.id());
        context.profiles().save(profile);
        if (!profile.rememberPassword()) {
            context.vault().removeSecret(profile.id());
        } else if (!password.isEmpty()) {
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
            context.connections().testConnection(profile, password);
            return Map.of("success", true);
        } finally {
            Arrays.fill(password, '\0');
        }
    }

    private Object connect(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        ConnectionProfile profile = requireProfile(profileId);
        BackendWorkspace existing = workspaces.get(profileId);
        if (existing != null) {
            return Map.of("profile", profileView(profile), "schemas", schemas(profileId));
        }
        char[] password = passwordFor(profile, params.path("password").asText(""));
        BackendWorkspace workspace = new BackendWorkspace(context, profile, password);
        Arrays.fill(password, '\0');
        try (Connection connection = workspace.openConnection()) {
            List<String> schemas = context.databaseAdapter().metadataProvider().listSchemas(connection);
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
        throw new RpcException("PASSWORD_REQUIRED", "请输入数据库密码",
                Map.of("profileId", profile.id(), "profileName", profile.name()));
    }

    private Object disconnect(String profileId) {
        closeQueries(profileId);
        BackendWorkspace workspace = workspaces.remove(profileId);
        if (workspace != null) workspace.close();
        return Map.of("disconnected", true);
    }

    private List<String> schemas(String profileId) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(profileId);
        try (Connection connection = workspace.openConnection()) {
            return context.databaseAdapter().metadataProvider().listSchemas(connection);
        }
    }

    private Object listObjects(JsonNode params) throws Exception {
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        String schema = requiredText(params, "schema");
        DatabaseObjectKind kind = DatabaseObjectKind.valueOf(requiredText(params, "kind"));
        try (Connection connection = workspace.openConnection()) {
            return context.databaseAdapter().metadataProvider().listObjects(connection, schema, kind);
        }
    }

    private Object createTable(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        DmTableDdlBuilder.Definition definition = tableDefinition(params);
        DmTableDdlBuilder ddl = new DmTableDdlBuilder(context.databaseAdapter());
        String sql = ddl.create(definition);
        BackendWorkspace workspace = requiredWorkspace(profileId);
        try (Connection connection = workspace.openConnection(); var statement = connection.createStatement()) {
            statement.execute(sql);
            for (String comment : ddl.createComments(definition)) statement.execute(comment);
        }
        return Map.of("created", true, "sql", sql);
    }

    private Object alterTable(JsonNode params) throws Exception {
        String profileId = requiredText(params, "profileId");
        DmTableDdlBuilder.Definition original = tableDefinition(params.path("original"));
        DmTableDdlBuilder.Definition target = tableDefinition(params.path("target"));
        DmTableDdlBuilder ddl = new DmTableDdlBuilder(context.databaseAdapter());
        List<String> statements = ddl.alter(original, target);
        BackendWorkspace workspace = requiredWorkspace(profileId);
        try (Connection connection = workspace.openConnection(); var statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
        return Map.of("altered", true, "statements", statements);
    }

    private Object longRowStatus(JsonNode params) throws Exception {
        DatabaseObject table = tableObject(params);
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        try (Connection connection = workspace.openConnection()) {
            String ddl = context.databaseAdapter().ddlProvider().getDdl(connection, table);
            return Map.of("enabled", ddl != null && ddl.toUpperCase(java.util.Locale.ROOT).matches("(?s).*\\bUSING\\s+LONG\\s+ROW\\b.*"));
        }
    }

    private Object setLongRow(JsonNode params) throws Exception {
        DatabaseObject table = tableObject(params);
        boolean enabled = params.path("enabled").asBoolean();
        BackendWorkspace workspace = requiredWorkspace(requiredText(params, "profileId"));
        String source = context.databaseAdapter().quoteIdentifier(table.schema()) + "."
                + context.databaseAdapter().quoteIdentifier(table.name());
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
                    item.path("primaryKey").asBoolean(false), item.path("autoIncrement").asBoolean(false), nullableText(item, "defaultExpression"), nullableText(item, "remark")));
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
                    details = context.databaseAdapter().metadataProvider().describeTable(connection, object);
                } catch (Exception exception) {
                    detailsError = "读取表结构失败：" + rootMessage(exception);
                }
                try {
                    preview = context.databaseAdapter().metadataProvider().previewTable(connection, object, 0, 100);
                } catch (Exception exception) {
                    previewError = "读取数据预览失败：" + rootMessage(exception);
                }
            }
            try {
                ddl = context.databaseAdapter().ddlProvider().getDdl(connection, object);
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
            TablePreview preview = context.databaseAdapter().metadataProvider()
                    .previewTable(connection, object, (page - 1) * pageSize, pageSize, filter);
            long lastPage = Math.max(1, (preview.totalRows() + pageSize - 1) / pageSize);
            int safePage = (int) Math.min(page, lastPage);
            if (safePage != page) {
                preview = context.databaseAdapter().metadataProvider()
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
            return Map.of("updated", updateObjectCell(connection, object, requiredText(params, "column"),
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
                    updated += updateObjectCell(connection, object, requiredText(change, "column"),
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

    private int updateObjectCell(Connection connection, DatabaseObject object, String requestedColumn,
                                 JsonNode value, JsonNode keyValues) throws Exception {
        if (object.kind() != DatabaseObjectKind.TABLE) {
            throw new RpcException("INVALID_OBJECT", "只有数据表支持直接编辑");
        }
        if (!keyValues.isObject()) throw new RpcException("INVALID_REQUEST", "缺少用于定位记录的主键值");

        DatabaseMetaData metadata = connection.getMetaData();
        List<String> columns = new ArrayList<>();
        try (var rs = metadata.getColumns(null, object.schema(), object.name(), "%")) {
            while (rs.next()) columns.add(rs.getString("COLUMN_NAME"));
        }
        String column = columns.stream().filter(name -> name.equalsIgnoreCase(requestedColumn)).findFirst()
                .orElseThrow(() -> new RpcException("INVALID_COLUMN", "找不到要更新的列：" + requestedColumn));
        List<String> primaryKeys = new ArrayList<>();
        try (var rs = metadata.getPrimaryKeys(null, object.schema(), object.name())) {
            while (rs.next()) primaryKeys.add(rs.getString("COLUMN_NAME"));
        }
        if (primaryKeys.isEmpty()) throw new RpcException("PRIMARY_KEY_REQUIRED", "该表没有主键，无法安全地直接编辑数据");

        String source = context.databaseAdapter().quoteIdentifier(object.schema()) + "."
                + context.databaseAdapter().quoteIdentifier(object.name());
        String where = primaryKeys.stream().map(key -> context.databaseAdapter().quoteIdentifier(key) + " = ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "UPDATE " + source + " SET " + context.databaseAdapter().quoteIdentifier(column) + " = ? WHERE " + where;
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

    private Object openQuery(String profileId) throws Exception {
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
        List<String> statements = switch (mode) {
            case "SELECTION" -> parser.split(params.path("selection").asText(""));
            case "CURRENT_STATEMENT" -> parser.currentStatement(sql, params.path("caretOffset").asInt(0))
                    .map(statement -> List.of(statement.sql())).orElseGet(List::of);
            case "SCRIPT" -> parser.split(sql);
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
            long rows = InsertSqlExporter.write(connection, context.databaseAdapter(), table, previewFilter(params), offset, maxRows, target);
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
            long rows = TableCsvExporter.write(connection, context.databaseAdapter(), table, previewFilter(params), "CURRENT_PAGE".equals(scope) ? (page - 1) * pageSize : 0, "CURRENT_PAGE".equals(scope) ? pageSize : 0, Path.of(requiredText(params, "path")));
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
            statement.setObject(index, value.bigIntegerValue());
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

    private ConnectionProfile profileFrom(JsonNode params) {
        String id = params.path("id").asText("").strip();
        String name = requiredText(params, "name").strip();
        String host = requiredText(params, "host").strip();
        int port = params.path("port").asInt(5236);
        String username = requiredText(params, "username").strip();
        String driverId = requiredText(params, "driverId");
        Map<String, String> properties = new LinkedHashMap<>();
        JsonNode advanced = params.path("advancedProperties");
        if (advanced.isObject()) advanced.fields().forEachRemaining(entry -> properties.put(entry.getKey(), entry.getValue().asText("")));
        boolean remember = params.path("rememberPassword").asBoolean(true);
        return id.isEmpty()
                ? ConnectionProfile.create(name, host, port, username, driverId, properties, remember)
                : new ConnectionProfile(id, name, "dm", host, port, username, driverId, properties, remember);
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
