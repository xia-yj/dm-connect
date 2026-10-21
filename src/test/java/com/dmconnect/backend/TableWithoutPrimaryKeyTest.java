package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(AppPaths.DATA_DIR_PROPERTY)
class TableWithoutPrimaryKeyTest {
    @TempDir
    Path temporary;

    private final ObjectMapper mapper = new JsonStore().mapper();

    @Test
    void editsAndDeletesRowsMatchedByTheirOriginalValues() throws Exception {
        Path database = temporary.resolve("data.db");
        execute(database, "CREATE TABLE MSG (ID INTEGER, NAME TEXT, N INTEGER)",
                "INSERT INTO MSG VALUES (1, 'a', 1), (2, 'b', 2), (3, 'dup', 3), (3, 'dup', 3), (4, NULL, 4)");
        withTable(database, (service, sessionId) -> {
            ArrayNode changes = mapper.createArrayNode();
            changes.add(update("NAME", "a2", row(1, "a", 1)));
            // A second edit of the same row matches on the value the first edit wrote.
            changes.add(update("N", 10, row(1, "a2", 1)));
            changes.add(mapper.createObjectNode().put("type", "DELETE").set("keyValues", row(2, "b", 2)));
            changes.add(update("NAME", "filled", row(4, null, 4)));
            service.handle("table.saveChanges", mapper.createObjectNode().put("sessionId", sessionId).set("changes", changes));
        });
        assertThat(rows(database)).containsExactly("1|a2|10", "3|dup|3", "3|dup|3", "4|filled|4");
    }

    @Test
    void rollsBackWhenTheOriginalValuesMatchSeveralRows() throws Exception {
        Path database = temporary.resolve("data.db");
        execute(database, "CREATE TABLE MSG (ID INTEGER, NAME TEXT, N INTEGER)",
                "INSERT INTO MSG VALUES (1, 'a', 1), (3, 'dup', 3), (3, 'dup', 3)");
        withTable(database, (service, sessionId) -> {
            ArrayNode changes = mapper.createArrayNode();
            changes.add(update("NAME", "changed", row(1, "a", 1)));
            changes.add(update("NAME", "changed", row(3, "dup", 3)));
            assertThatThrownBy(() -> service.handle("table.saveChanges", mapper.createObjectNode().put("sessionId", sessionId).set("changes", changes)))
                    .isInstanceOfSatisfying(RpcException.class, error -> {
                        assertThat(error.code()).isEqualTo("UPDATE_CONFLICT");
                        assertThat(error.getMessage()).contains("2 行数据完全相同");
                    });
        });
        assertThat(rows(database)).containsExactly("1|a|1", "3|dup|3", "3|dup|3");
    }

    @Test
    void singleRowFallbackRollsBackWhenSeveralRowsMatch() throws Exception {
        Path database = temporary.resolve("data.db");
        execute(database, "CREATE TABLE MSG (ID INTEGER, NAME TEXT, N INTEGER)",
                "INSERT INTO MSG VALUES (3, 'dup', 3), (3, 'dup', 3)");
        withTable(database, (service, sessionId) -> {
            ObjectNode request = mapper.createObjectNode().put("profileId", profileId).put("schema", "main").put("name", "MSG").put("kind", "TABLE");
            request.set("keyValues", row(3, "dup", 3));
            assertThatThrownBy(() -> service.handle("object.deleteRow", request))
                    .isInstanceOfSatisfying(RpcException.class, error -> assertThat(error.code()).isEqualTo("DELETE_CONFLICT"));
        });
        assertThat(rows(database)).containsExactly("3|dup|3", "3|dup|3");
    }

    private String profileId;

    private interface TableWork { void run(BackendService service, String sessionId) throws Exception; }

    private void withTable(Path database, TableWork work) throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.resolve("app").toString());
        try (AppContext context = new AppContext(); BackendService service = new BackendService(context, mapper)) {
            Map<?, ?> bootstrap = (Map<?, ?>) service.handle("app.bootstrap", mapper.createObjectNode());
            String driverId = ((List<?>) bootstrap.get("drivers")).stream().map(item -> (Map<?, ?>) item)
                    .filter(item -> "org.sqlite.JDBC".equals(item.get("driverClass"))).map(item -> String.valueOf(item.get("id")))
                    .findFirst().orElseThrow();
            Map<?, ?> profile = (Map<?, ?>) service.handle("profile.save", mapper.createObjectNode()
                    .put("name", "sqlite").put("databaseType", "sqlite").put("host", "localhost")
                    .put("database", database.toString()).put("driverId", driverId));
            profileId = String.valueOf(profile.get("id"));
            service.handle("connection.connect", mapper.createObjectNode().put("profileId", profileId));
            Map<?, ?> session = (Map<?, ?>) service.handle("table.open", mapper.createObjectNode()
                    .put("profileId", profileId).put("schema", "main").put("name", "MSG"));
            work.run(service, String.valueOf(session.get("sessionId")));
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }

    private ObjectNode update(String column, Object value, ObjectNode keyValues) {
        ObjectNode change = mapper.createObjectNode().put("type", "UPDATE").put("column", column);
        change.set("value", mapper.valueToTree(value));
        change.set("keyValues", keyValues);
        return change;
    }

    private ObjectNode row(int id, String name, int n) {
        ObjectNode row = mapper.createObjectNode().put("ID", id);
        if (name == null) row.putNull("NAME");
        else row.put("NAME", name);
        return row.put("N", n);
    }

    private static void execute(Path database, String... statements) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement()) {
            for (String sql : statements) statement.execute(sql);
        }
    }

    private static List<String> rows(Path database) throws Exception {
        List<String> rows = new ArrayList<>();
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database); Statement statement = connection.createStatement();
             var result = statement.executeQuery("SELECT ID, NAME, N FROM MSG ORDER BY ID, N")) {
            while (result.next()) rows.add(result.getInt(1) + "|" + result.getString(2) + "|" + result.getInt(3));
        }
        return rows;
    }
}
