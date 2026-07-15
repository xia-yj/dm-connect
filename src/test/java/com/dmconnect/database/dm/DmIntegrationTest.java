package com.dmconnect.database.dm;

import com.dmconnect.connection.ConnectionService;
import com.dmconnect.connection.DriverManagerService;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class DmIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void validatesMetadataTransactionsLobsDdlAndPreviewAgainstRealDm() throws Exception {
        String host = env("DM_TEST_HOST");
        String portText = env("DM_TEST_PORT");
        String user = env("DM_TEST_USER");
        String password = env("DM_TEST_PASSWORD");
        String schema = env("DM_TEST_SCHEMA");
        String driverJar = env("DM_JDBC_JAR");
        Assumptions.assumeTrue(!host.isBlank() && !portText.isBlank() && !user.isBlank()
                && !password.isBlank() && !schema.isBlank() && !driverJar.isBlank(),
                "未配置完整达梦集成测试环境变量");

        AppPaths paths = new AppPaths(temporary, temporary.resolve("drivers"), temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"), temporary.resolve("vault.json"));
        paths.initialize();
        DmDatabaseAdapter adapter = new DmDatabaseAdapter();
        try (DriverManagerService drivers = new DriverManagerService(paths, new JsonStore())) {
            var descriptor = drivers.importDriver(Path.of(driverJar));
            ConnectionProfile profile = ConnectionProfile.create("integration", host, Integer.parseInt(portText),
                    user, descriptor.id(), Map.of("appName", "DM Connect Integration Test"), false);
            try (ConnectionService connections = new ConnectionService(adapter, drivers);
                 Connection connection = connections.connect(profile, password.toCharArray())) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isNotBlank();
                assertThat(adapter.metadataProvider().listSchemas(connection)).isNotEmpty();

                String name = "DM_CONNECT_IT_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
                String qualified = adapter.quoteIdentifier(schema) + "." + adapter.quoteIdentifier(name);
                DatabaseObject table = new DatabaseObject(schema, name, DatabaseObjectKind.TABLE, "");
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE " + qualified
                                + " (ID INT PRIMARY KEY, NAME VARCHAR(100), CREATED_AT TIMESTAMP, NOTE CLOB, BIN BLOB)");
                    }
                    connection.setAutoCommit(false);
                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + qualified + " (ID, NAME, CREATED_AT, NOTE, BIN) VALUES (?, ?, ?, ?, ?)")) {
                        insert.setInt(1, 1);
                        insert.setString(2, "中文数据");
                        insert.setTimestamp(3, java.sql.Timestamp.from(Instant.now()));
                        insert.setString(4, "CLOB 内容");
                        insert.setBytes(5, new byte[]{1, 2, 3});
                        insert.executeUpdate();
                    }
                    connection.rollback();
                    try (Statement statement = connection.createStatement();
                         var rs = statement.executeQuery("SELECT COUNT(*) FROM " + qualified)) {
                        assertThat(rs.next()).isTrue();
                        assertThat(rs.getInt(1)).isZero();
                    }
                    connection.setAutoCommit(true);
                    assertThat(adapter.metadataProvider().describeTable(connection, table).columns()).hasSize(5);
                    assertThat(adapter.ddlProvider().getDdl(connection, table)).containsIgnoringCase("CREATE");
                    assertThat(adapter.metadataProvider().previewTable(connection, table, 0, 100).table().rows()).isEmpty();
                } finally {
                    connection.setAutoCommit(true);
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP TABLE " + qualified);
                    }
                }
            }
        }
    }

    private static String env(String name) {
        return System.getenv().getOrDefault(name, "").strip();
    }
}
