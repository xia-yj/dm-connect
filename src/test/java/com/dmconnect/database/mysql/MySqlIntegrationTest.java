package com.dmconnect.database.mysql;

import com.dmconnect.connection.ConnectionService;
import com.dmconnect.connection.DriverManagerService;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.dmconnect.query.InsertSqlExporter;
import com.dmconnect.query.TableCsvExporter;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("integration")
class MySqlIntegrationTest {
    @TempDir
    Path temporary;

    @Test
    void connectsCreatesDescribesPreviewsReadsDdlAndDropsAgainstRealMysql() throws Exception {
        String host = env("MYSQL_TEST_HOST");
        String portText = env("MYSQL_TEST_PORT");
        String user = env("MYSQL_TEST_USER");
        String password = env("MYSQL_TEST_PASSWORD");
        String database = env("MYSQL_TEST_DATABASE");
        String driverJar = env("MYSQL_JDBC_JAR");
        Assumptions.assumeTrue(!host.isBlank() && !portText.isBlank() && !user.isBlank()
                        && !password.isBlank() && !database.isBlank() && !driverJar.isBlank(),
                "未配置完整 MySQL 集成测试环境变量");

        AppPaths paths = new AppPaths(temporary, temporary.resolve("drivers"), temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"), temporary.resolve("vault.json"));
        paths.initialize();
        MySqlDatabaseAdapter adapter = new MySqlDatabaseAdapter();
        try (DriverManagerService drivers = new DriverManagerService(paths, new JsonStore())) {
            var descriptor = drivers.importDriver(Path.of(driverJar), adapter);
            ConnectionProfile profile = ConnectionProfile.create("mysql", "integration", host,
                    Integer.parseInt(portText), database, user, descriptor.id(),
                    Map.of("connectTimeout", "10000", "socketTimeout", "30000"), false);
            try (ConnectionService connections = new ConnectionService(adapter, drivers);
                 Connection connection = connections.connect(profile, password.toCharArray())) {
                assertThat(connection.getMetaData().getDatabaseProductName()).isNotBlank();
                assertThat(adapter.metadataProvider().listSchemas(connection))
                        .anyMatch(schema -> schema.equalsIgnoreCase(database));

                String name = "MYSQL_CONNECT_IT_"
                        + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase();
                String qualified = adapter.quoteIdentifier(database) + "." + adapter.quoteIdentifier(name);
                DatabaseObject table = new DatabaseObject(database, name, DatabaseObjectKind.TABLE, "");
                boolean created = false;
                try {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("CREATE TABLE " + qualified
                                + " (`ID` BIGINT NOT NULL AUTO_INCREMENT, `NAME` VARCHAR(100) NOT NULL, "
                                + "`CREATED_AT` TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6), "
                                + "`REPORT_YEAR` YEAR, `ELAPSED` TIME(6), "
                                + "`NOTE` LONGTEXT, `BIN` LONGBLOB, PRIMARY KEY (`ID`))");
                    }
                    created = true;

                    try (PreparedStatement insert = connection.prepareStatement(
                            "INSERT INTO " + qualified + " (`NAME`, `REPORT_YEAR`, `ELAPSED`, `NOTE`, `BIN`) VALUES (?, ?, ?, ?, ?)") ) {
                        insert.setString(1, "中文数据");
                        insert.setString(2, "2024");
                        insert.setString(3, "36:01:02.123456");
                        insert.setString(4, "LONGTEXT 内容");
                        insert.setBytes(5, new byte[]{1, 2, 3});
                        assertThat(insert.executeUpdate()).isEqualTo(1);
                    }

                    var details = adapter.metadataProvider().describeTable(connection, table);
                    assertThat(details.columns()).hasSize(7);
                    assertThat(details.columns()).extracting("name")
                            .containsExactly("ID", "NAME", "CREATED_AT", "REPORT_YEAR", "ELAPSED", "NOTE", "BIN");
                    assertThat(details.constraints()).anyMatch(constraint ->
                            constraint.type().equalsIgnoreCase("PRIMARY KEY")
                                    && constraint.columns().stream().anyMatch("ID"::equalsIgnoreCase));

                    var preview = adapter.metadataProvider().previewTable(connection, table, 0, 100);
                    assertThat(preview.totalRows()).isEqualTo(1);
                    assertThat(preview.table().rows()).hasSize(1);
                    int yearIndex = columnIndex(preview.table(), "REPORT_YEAR");
                    int elapsedIndex = columnIndex(preview.table(), "ELAPSED");
                    assertThat(preview.table().rows().get(0).get(yearIndex)).isEqualTo("2024");
                    assertThat(preview.table().rows().get(0).get(elapsedIndex)).isEqualTo("36:01:02.123456");

                    Path inserts = temporary.resolve("mysql-export.sql");
                    Path csv = temporary.resolve("mysql-export.csv");
                    assertThat(InsertSqlExporter.write(connection, adapter, table, null, 0, 0, inserts)).isEqualTo(1);
                    assertThat(TableCsvExporter.write(connection, adapter, table, null, 0, 0, csv)).isEqualTo(1);
                    assertThat(Files.readString(inserts)).contains("`ID`", "X'010203'",
                            "CONVERT(X'32303234' USING utf8mb4)",
                            "CONVERT(X'33363a30313a30322e313233343536' USING utf8mb4)");
                    assertThat(Files.readString(csv)).contains("2024", "36:01:02.123456", "0x010203");
                    assertThat(adapter.ddlProvider().getDdl(connection, table))
                            .containsIgnoringCase("CREATE TABLE")
                            .containsIgnoringCase(name);

                    try (Statement statement = connection.createStatement()) {
                        statement.execute("DROP TABLE " + qualified);
                    }
                    created = false;
                    try (ResultSet rows = connection.getMetaData().getTables(database, null, name,
                            new String[]{"TABLE"})) {
                        assertThat(rows.next()).isFalse();
                    }
                } finally {
                    if (created) {
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("DROP TABLE " + qualified);
                        }
                    }
                }
            }
        }
    }

    private static String env(String name) {
        return System.getenv().getOrDefault(name, "").strip();
    }

    private static int columnIndex(com.dmconnect.model.ResultTable table, String name) {
        for (int index = 0; index < table.columns().size(); index++) {
            if (table.columns().get(index).name().equalsIgnoreCase(name)
                    || table.columns().get(index).label().equalsIgnoreCase(name)) return index;
        }
        throw new AssertionError("未找到列：" + name);
    }
}
