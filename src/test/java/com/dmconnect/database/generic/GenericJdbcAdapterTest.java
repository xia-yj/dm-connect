package com.dmconnect.database.generic;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.sql.DriverManager;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.PreviewFilter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GenericJdbcAdapterTest {
    @Test
    void buildsVendorUrlsAndEscapesIdentifiers() {
        var postgres = new GenericJdbcAdapter("postgresql", "PostgreSQL", "org.postgresql.Driver", 5432, GenericJdbcAdapter.Flavor.POSTGRESQL);
        var oracle = new GenericJdbcAdapter("oracle", "Oracle", "oracle.jdbc.OracleDriver", 1521, GenericJdbcAdapter.Flavor.ORACLE);
        var sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, GenericJdbcAdapter.Flavor.SQLSERVER);
        var sqlite = new GenericJdbcAdapter("sqlite", "SQLite", "org.sqlite.JDBC", 1, GenericJdbcAdapter.Flavor.SQLITE);
        assertThat(postgres.buildJdbcUrl("db.local", 5432, "app", Map.of("sslmode", "prefer"))).isEqualTo("jdbc:postgresql://db.local:5432/app");
        assertThat(oracle.buildJdbcUrl("db.local", 1521, "ORCLPDB1", Map.of())).isEqualTo("jdbc:oracle:thin:@//db.local:1521/ORCLPDB1");
        assertThat(sqlserver.buildJdbcUrl("db.local", 1433, "app", Map.of("encrypt", "true"))).isEqualTo("jdbc:sqlserver://db.local:1433;databaseName=app");
        assertThat(postgres.connectionProperties(Map.of("sslmode", "prefer"))).containsEntry("sslmode", "prefer");
        assertThat(sqlserver.connectionProperties(Map.of("encrypt", "true"))).containsEntry("encrypt", "true");
        assertThat(sqlite.buildJdbcUrl("localhost", 1, "/tmp/app.db", Map.of())).isEqualTo("jdbc:sqlite:/tmp/app.db");
        assertThat(sqlserver.quoteIdentifier("a]b")).isEqualTo("[a]]b]");
        assertThatThrownBy(() -> sqlite.buildJdbcUrl("localhost", 1, "", Map.of())).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void validatesAndEncodesVendorSpecificUrlParts() {
        var postgres = new GenericJdbcAdapter("postgresql", "PostgreSQL", "org.postgresql.Driver", 5432, GenericJdbcAdapter.Flavor.POSTGRESQL);
        var oracle = new GenericJdbcAdapter("oracle", "Oracle", "oracle.jdbc.OracleDriver", 1521, GenericJdbcAdapter.Flavor.ORACLE);
        var sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, GenericJdbcAdapter.Flavor.SQLSERVER);
        var sqlite = new GenericJdbcAdapter("sqlite", "SQLite", "org.sqlite.JDBC", 1, GenericJdbcAdapter.Flavor.SQLITE);

        assertThat(postgres.buildJdbcUrl("2001:db8::1", 5432, "app db", Map.of("ApplicationName", "连接工具")))
                .isEqualTo("jdbc:postgresql://[2001:db8::1]:5432/app%20db");
        assertThat(sqlserver.buildJdbcUrl("db.local", 1433, "app;readonly=true", Map.of("applicationName", "a}b;c")))
                .isEqualTo("jdbc:sqlserver://db.local:1433;databaseName={app;readonly=true}");
        assertThat(sqlserver.connectionProperties(Map.of("applicationName", "a}b;c")))
                .containsEntry("applicationName", "a}b;c");
        assertThatThrownBy(() -> postgres.buildJdbcUrl("db.local/path", 5432, "app", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("主机");
        assertThatThrownBy(() -> postgres.buildJdbcUrl("db.local:5432", 5432, "app", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("端口");
        assertThatThrownBy(() -> oracle.buildJdbcUrl("db.local", 1521, "ORCL?user=bad", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("服务名");
        assertThatThrownBy(() -> sqlserver.buildJdbcUrl("db.local", 1433, "app", Map.of("bad;key", "value")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("参数名");
        assertThatThrownBy(() -> sqlserver.connectionProperties(Map.of("accessToken", "must-not-persist")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("凭据");
        assertThatThrownBy(() -> sqlserver.connectionProperties(Map.of("databaseName", "other")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("核心连接字段");
        assertThatThrownBy(() -> sqlite.buildJdbcUrl("localhost", 1, "/tmp/app.db", Map.of("mode", "ro")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("高级 JDBC 参数");
    }

    @Test
    void browsesAndPreviewsSqliteThroughStandardJdbcMetadata() throws Exception {
        var sqlite = new GenericJdbcAdapter("sqlite", "SQLite", "org.sqlite.JDBC", 1, GenericJdbcAdapter.Flavor.SQLITE);
        try (var connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            connection.createStatement().execute("PRAGMA foreign_keys = ON");
            connection.createStatement().execute("CREATE TABLE parent (id INTEGER PRIMARY KEY)");
            connection.createStatement().execute("INSERT INTO parent VALUES (1)");
            String createSql = "CREATE TABLE sample (id INTEGER PRIMARY KEY, name TEXT NOT NULL, code TEXT UNIQUE, parent_id INTEGER REFERENCES parent(id))";
            connection.createStatement().execute(createSql);
            connection.createStatement().execute("CREATE TRIGGER sample_touch AFTER INSERT ON sample BEGIN UPDATE sample SET name = name WHERE id = NEW.id; END");
            connection.createStatement().execute("INSERT INTO sample (id, name, parent_id) VALUES (1, 'one', 1)");
            var objects = sqlite.metadataProvider().listObjects(connection, "main", DatabaseObjectKind.TABLE);
            assertThat(objects).extracting(DatabaseObject::name).contains("sample");
            var table = new DatabaseObject("main", "sample", DatabaseObjectKind.TABLE, "");
            assertThat(sqlite.metadataProvider().previewTable(connection, table, 0, 20).table().rows()).hasSize(1);
            assertThat(sqlite.metadataProvider().previewTable(connection, table, 0, 20,
                    new PreviewFilter(java.util.List.of(new PreviewFilter.Condition("name", "=", "one"))))
                    .table().rows()).hasSize(1);
            assertThat(sqlite.metadataProvider().describeTable(connection, table).constraints())
                    .anySatisfy(constraint -> assertThat(constraint.type()).isEqualTo("PRIMARY KEY"));
            assertThat(sqlite.metadataProvider().describeTable(connection, table).constraints())
                    .anySatisfy(constraint -> assertThat(constraint.type()).isEqualTo("FOREIGN KEY"));
            assertThat(sqlite.metadataProvider().describeTable(connection, table).indexes()).isNotEmpty();
            assertThat(sqlite.metadataProvider().listObjects(connection, "main", DatabaseObjectKind.TRIGGER))
                    .extracting(DatabaseObject::name).contains("sample_touch");
            assertThat(sqlite.ddlProvider().getDdl(connection, table)).isEqualTo(createSql + ";");
        }
    }

    @Test
    void sqlServerPreviewUsesDeterministicPrimaryKeyOrdering() throws Exception {
        var sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433,
                GenericJdbcAdapter.Flavor.SQLSERVER);
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:sqlserver_preview;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE SCHEMA APP");
            connection.createStatement().execute(
                    "CREATE TABLE APP.SAMPLE (ID INT PRIMARY KEY, NAME VARCHAR(30))");
            connection.createStatement().execute(
                    "INSERT INTO APP.SAMPLE VALUES (30, 'thirty'), (10, 'ten'), (20, 'twenty')");

            var table = new DatabaseObject("APP", "SAMPLE", DatabaseObjectKind.TABLE, "");
            var preview = sqlserver.metadataProvider().previewTable(connection, table, 1, 1);

            assertThat(preview.totalRows()).isEqualTo(3);
            assertThat(preview.table().rows()).containsExactly(java.util.List.of(20, "twenty"));
        }
    }

    @Test
    void sqlServerPreviewFallsBackToSortableColumnsWhenNoKeyExists() throws Exception {
        var sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433,
                GenericJdbcAdapter.Flavor.SQLSERVER);
        try (var connection = DriverManager.getConnection(
                "jdbc:h2:mem:sqlserver_no_key;MODE=MSSQLServer;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE SCHEMA APP");
            connection.createStatement().execute(
                    "CREATE TABLE APP.NO_KEY (ID INT, NAME VARCHAR(30))");
            connection.createStatement().execute(
                    "INSERT INTO APP.NO_KEY VALUES (2, 'two'), (1, 'one')");

            var table = new DatabaseObject("APP", "NO_KEY", DatabaseObjectKind.TABLE, "");
            var preview = sqlserver.metadataProvider().previewTable(connection, table, 0, 1);

            assertThat(preview.table().rows()).containsExactly(java.util.List.of(1, "one"));
        }
    }

    @Test
    void doesNotPresentReconstructedPostgresOrSqlServerTableDdlAsComplete() throws Exception {
        var postgres = new GenericJdbcAdapter("postgresql", "PostgreSQL", "org.postgresql.Driver",
                5432, GenericJdbcAdapter.Flavor.POSTGRESQL);
        var sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433,
                GenericJdbcAdapter.Flavor.SQLSERVER);

        var postgresTable = new DatabaseObject("public", "sample", DatabaseObjectKind.TABLE, "");
        var sqlServerTable = new DatabaseObject("dbo", "sample", DatabaseObjectKind.TABLE, "");

        assertThat(postgres.ddlProvider().getDdl(null, postgresTable))
                .startsWith("-- 未生成")
                .contains("pg_dump --schema-only")
                .doesNotContain("\nCREATE TABLE");
        assertThat(sqlserver.ddlProvider().getDdl(null, sqlServerTable))
                .startsWith("-- 未生成")
                .contains("生成脚本", "SMO Scripter")
                .doesNotContain("\nCREATE TABLE");
    }
}
