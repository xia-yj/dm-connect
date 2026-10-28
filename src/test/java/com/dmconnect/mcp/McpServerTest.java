package com.dmconnect.mcp;

import com.dmconnect.AppContext;
import com.dmconnect.backend.BackendService;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock(AppPaths.DATA_DIR_PROPERTY)
class McpServerTest {
    @TempDir
    Path temporary;

    private final ObjectMapper mapper = new JsonStore().mapper();

    @Test
    void answersInitializeAndListsToolsOverStdio() throws Exception {
        withServer(McpTools.Access.READ_ONLY, server -> {
            JsonNode initialize = server.request("initialize", mapper.createObjectNode().put("protocolVersion", "2025-03-26"));
            assertThat(initialize.path("result").path("protocolVersion").asText()).isEqualTo("2025-03-26");
            assertThat(initialize.path("result").path("capabilities").has("tools")).isTrue();
            assertThat(server.notify("notifications/initialized")).isEmpty();

            JsonNode tools = server.request("tools/list", mapper.createObjectNode()).path("result").path("tools");
            assertThat(tools.findValuesAsText("name")).contains("list_connections", "describe_object", "execute_sql");
            assertThat(tools.findValuesAsText("name")).doesNotContain("mongo_insert", "redis_command");

            JsonNode unknown = server.request("resources/list", mapper.createObjectNode());
            assertThat(unknown.path("error").path("code").asInt()).isEqualTo(-32601);
        });
    }

    @Test
    void browsesAndQueriesSqliteReadOnly() throws Exception {
        withServer(McpTools.Access.READ_ONLY, server -> {
            JsonNode connections = server.callTool("list_connections", mapper.createObjectNode());
            assertThat(connections.path("readOnly").asBoolean()).isTrue();
            assertThat(connections.path("connections").get(0).path("name").asText()).isEqualTo("本地库");

            JsonNode objects = server.callTool("list_objects", args().put("schema", "main"));
            assertThat(objects.path("objects").findValuesAsText("name")).contains("users");

            JsonNode described = server.callTool("describe_object", args().put("schema", "main").put("name", "users"));
            assertThat(described.path("details").path("columns").findValuesAsText("name")).contains("id", "name");
            assertThat(described.has("preview")).isFalse();

            JsonNode preview = server.callTool("preview_table", args().put("schema", "main").put("name", "users"));
            assertThat(preview.path("totalRows").asLong()).isEqualTo(2);

            JsonNode selected = server.callTool("execute_sql", args().put("sql", "SELECT name FROM users ORDER BY id"));
            assertThat(selected.path("results").get(0).path("columns").get(0).asText()).isEqualTo("name");
            assertThat(selected.path("results").get(0).path("rows").get(1).get(0).asText()).isEqualTo("李四");

            JsonNode rejected = server.callToolRaw("execute_sql", args().put("sql", "INSERT INTO users(name) VALUES ('王五')"));
            assertThat(rejected.path("isError").asBoolean()).isTrue();
            assertThat(rejected.path("content").get(0).path("text").asText()).contains("只读模式", "INSERT INTO users");
            assertThat(userCount()).isEqualTo(2);
        });
    }

    @Test
    void writesOnlyWhenAllowedAndStillBlocksDangerousSql() throws Exception {
        withServer(McpTools.Access.WRITE, server -> {
            JsonNode inserted = server.callTool("execute_sql", args().put("sql", "INSERT INTO users(name) VALUES ('王五')"));
            assertThat(inserted.path("results").get(0).path("updateCount").asInt()).isEqualTo(1);
            assertThat(userCount()).isEqualTo(3);

            JsonNode dropped = server.callToolRaw("execute_sql", args().put("sql", "DROP TABLE users"));
            assertThat(dropped.path("isError").asBoolean()).isTrue();
            assertThat(userCount()).isEqualTo(3);
        });
    }

    @Test
    void fullAccessRunsDangerousSqlAndListsWriteTools() throws Exception {
        withServer(McpTools.Access.FULL, server -> {
            JsonNode tools = server.request("tools/list", mapper.createObjectNode()).path("result").path("tools");
            assertThat(tools.findValuesAsText("name")).contains("mongo_insert", "mongo_replace", "mongo_delete", "redis_command");
            assertThat(server.callTool("list_connections", mapper.createObjectNode()).path("access").asText()).isEqualTo("FULL");

            server.callTool("execute_sql", args().put("sql", "DELETE FROM users"));
            assertThat(userCount()).isZero();
            server.callTool("execute_sql", args().put("sql", "DROP TABLE users"));
            JsonNode missing = server.callToolRaw("execute_sql", args().put("sql", "SELECT COUNT(*) FROM users"));
            assertThat(missing.path("content").get(0).path("text").asText()).contains("users");
        });
    }

    @Test
    void reportsUnknownConnectionWithAvailableNames() throws Exception {
        withServer(McpTools.Access.READ_ONLY, server -> {
            JsonNode result = server.callToolRaw("list_schemas", mapper.createObjectNode().put("connection", "不存在"));
            assertThat(result.path("isError").asBoolean()).isTrue();
            assertThat(result.path("content").get(0).path("text").asText()).contains("找不到连接", "本地库");
        });
    }

    private ObjectNode args() {
        return mapper.createObjectNode().put("connection", "本地库");
    }

    private Path database() {
        return temporary.resolve("app.db");
    }

    private long userCount() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             ResultSet rows = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private void withServer(McpTools.Access access, ServerAction action) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database());
             var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE users (id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.execute("INSERT INTO users(name) VALUES ('张三'), ('李四')");
        }
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.resolve("data").toString());
        try (AppContext context = new AppContext();
             BackendService service = new BackendService(context, mapper, false)) {
            service.handle("profile.save", mapper.createObjectNode()
                    .put("name", "本地库")
                    .put("databaseType", "sqlite")
                    .put("host", "localhost")
                    .put("database", database().toString())
                    .put("driverId", "builtin:sqlite"));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (McpServer server = new McpServer(new McpTools(context, service, mapper, access), mapper,
                    new ByteArrayInputStream(new byte[0]), output)) {
                action.run(new Client(server, output));
            }
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }

    @FunctionalInterface
    private interface ServerAction {
        void run(Client client) throws Exception;
    }

    private final class Client {
        private final McpServer server;
        private final ByteArrayOutputStream output;
        private int sequence;

        Client(McpServer server, ByteArrayOutputStream output) {
            this.server = server;
            this.output = output;
        }

        JsonNode request(String method, JsonNode params) throws Exception {
            ObjectNode request = mapper.createObjectNode().put("jsonrpc", "2.0").put("id", ++sequence).put("method", method);
            request.set("params", params);
            String written = send(request);
            assertThat(written).doesNotContain("\n").isNotBlank();
            JsonNode response = mapper.readTree(written);
            assertThat(response.path("id").asInt()).isEqualTo(sequence);
            return response;
        }

        String notify(String method) throws Exception {
            return send(mapper.createObjectNode().put("jsonrpc", "2.0").put("method", method));
        }

        JsonNode callToolRaw(String name, JsonNode arguments) throws Exception {
            ObjectNode params = mapper.createObjectNode().put("name", name);
            params.set("arguments", arguments);
            return request("tools/call", params).path("result");
        }

        JsonNode callTool(String name, JsonNode arguments) throws Exception {
            JsonNode result = callToolRaw(name, arguments);
            String text = result.path("content").get(0).path("text").asText();
            assertThat(result.path("isError").asBoolean()).as(text).isFalse();
            return mapper.readTree(text);
        }

        private String send(JsonNode message) throws Exception {
            output.reset();
            server.process(mapper.writeValueAsString(message));
            return output.toString(StandardCharsets.UTF_8).strip();
        }
    }
}
