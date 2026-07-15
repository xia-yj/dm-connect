package com.dmconnect.database.generic;

import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;

import java.io.IOException;
import java.io.Reader;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads definitions from vendor-owned catalog APIs. This class intentionally does not rebuild a
 * CREATE TABLE statement from JDBC column metadata: such a reconstruction silently loses keys,
 * indexes, generated columns, partitioning, storage settings, and vendor-specific type details.
 */
final class GenericJdbcDdl implements DdlProvider {
    private static final String ORACLE_DDL_SQL =
            "SELECT DBMS_METADATA.GET_DDL(?, ?, ?) FROM DUAL";
    private static final String SQL_SERVER_MODULE_SQL =
            "SELECT OBJECT_DEFINITION(OBJECT_ID(?))";
    private static final String POSTGRES_VIEW_SQL = """
            SELECT c.relkind, pg_catalog.pg_get_viewdef(c.oid, true)
              FROM pg_catalog.pg_class c
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ?
               AND c.relname = ?
               AND c.relkind IN ('v', 'm')
            """;
    private static final String POSTGRES_FUNCTION_SQL = """
            SELECT pg_catalog.pg_get_functiondef(p.oid)
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = ?
               AND p.proname = ?
               AND p.prokind IN ('f', 'w')
             ORDER BY pg_catalog.pg_get_function_identity_arguments(p.oid)
            """;
    private static final String POSTGRES_LEGACY_FUNCTION_SQL = """
            SELECT pg_catalog.pg_get_functiondef(p.oid)
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = ?
               AND p.proname = ?
               AND NOT p.proisagg
             ORDER BY pg_catalog.pg_get_function_identity_arguments(p.oid)
            """;
    private static final String POSTGRES_PROCEDURE_SQL = """
            SELECT pg_catalog.pg_get_functiondef(p.oid)
              FROM pg_catalog.pg_proc p
              JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
             WHERE n.nspname = ?
               AND p.proname = ?
               AND p.prokind = 'p'
             ORDER BY pg_catalog.pg_get_function_identity_arguments(p.oid)
            """;
    private static final String POSTGRES_TRIGGER_SQL = """
            SELECT pg_catalog.pg_get_triggerdef(t.oid, true)
              FROM pg_catalog.pg_trigger t
              JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
              JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
             WHERE n.nspname = ?
               AND t.tgname = ?
               AND NOT t.tgisinternal
             ORDER BY c.relname
            """;

    private final GenericJdbcAdapter adapter;

    GenericJdbcDdl(GenericJdbcAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String getDdl(Connection connection, DatabaseObject object) throws SQLException {
        return switch (adapter.flavor()) {
            case SQLITE -> sqliteDefinition(connection, object);
            case ORACLE -> oracleDefinition(connection, object);
            case POSTGRESQL -> postgresDefinition(connection, object);
            case SQLSERVER -> sqlServerDefinition(connection, object);
        };
    }

    private String sqliteDefinition(Connection connection, DatabaseObject object) throws SQLException {
        String sqliteType = switch (object.kind()) {
            case TABLE -> "table";
            case VIEW -> "view";
            case TRIGGER -> "trigger";
            default -> throw unsupported(object,
                    "SQLite sqlite_schema 不保存该类型对象的定义");
        };
        String schema = object.schema().isBlank() ? "main" : object.schema();
        String sql = "SELECT sql FROM " + adapter.quoteIdentifier(schema)
                + ".sqlite_schema WHERE type = ? AND name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, sqliteType);
            statement.setString(2, object.name());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw notFound(object);
                }
                String definition = rows.getString(1);
                if (definition == null || definition.isBlank()) {
                    throw new SQLException("SQLite 未为对象 " + displayName(object)
                            + " 保存可重建的 SQL 定义");
                }
                return terminate(definition);
            }
        }
    }

    private String oracleDefinition(Connection connection, DatabaseObject object) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(ORACLE_DDL_SQL)) {
            statement.setString(1, object.kind().ddlType());
            statement.setString(2, object.name());
            statement.setString(3, object.schema());
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw notFound(object);
                }
                String definition = readText(rows, 1);
                if (definition == null || definition.isBlank()) {
                    throw new SQLException("Oracle DBMS_METADATA 未返回 "
                            + displayName(object) + " 的 DDL");
                }
                return definition.strip();
            }
        }
    }

    private String postgresDefinition(Connection connection, DatabaseObject object) throws SQLException {
        return switch (object.kind()) {
            case VIEW -> postgresView(connection, object);
            case FUNCTION -> postgresRoutines(connection, object,
                    connection.getMetaData().getDatabaseMajorVersion() >= 11
                            ? POSTGRES_FUNCTION_SQL : POSTGRES_LEGACY_FUNCTION_SQL);
            case PROCEDURE -> {
                if (connection.getMetaData().getDatabaseMajorVersion() < 11) {
                    throw unsupported(object, "PostgreSQL 11 之前没有独立的存储过程对象");
                }
                yield postgresRoutines(connection, object, POSTGRES_PROCEDURE_SQL);
            }
            case TRIGGER -> postgresTriggers(connection, object);
            case TABLE -> unavailableTableDefinition(object,
                    "PostgreSQL 没有返回完整 CREATE TABLE 的服务器函数",
                    "请使用 pg_dump --schema-only --table 获取可还原的权威 DDL");
            case SEQUENCE -> throw unsupported(object,
                    "PostgreSQL 没有返回完整 CREATE SEQUENCE 的服务器函数，请使用 pg_dump --schema-only");
        };
    }

    private String postgresView(Connection connection, DatabaseObject object) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(POSTGRES_VIEW_SQL)) {
            bindNamespaceAndName(statement, object);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw notFound(object);
                }
                boolean materialized = "m".equals(rows.getString(1));
                String query = rows.getString(2);
                if (query == null || query.isBlank()) {
                    throw notFound(object);
                }
                String create = materialized ? "CREATE MATERIALIZED VIEW " : "CREATE OR REPLACE VIEW ";
                return "-- 视图查询由 PostgreSQL pg_get_viewdef 返回\n"
                        + create + qualifiedName(object) + " AS\n" + terminate(query);
            }
        }
    }

    private String postgresRoutines(Connection connection, DatabaseObject object,
                                    String sql) throws SQLException {
        List<String> definitions = queryDefinitions(connection, object, sql);
        if (definitions.isEmpty()) {
            throw notFound(object);
        }
        return String.join("\n\n", definitions);
    }

    private String postgresTriggers(Connection connection, DatabaseObject object) throws SQLException {
        List<String> definitions = queryDefinitions(connection, object, POSTGRES_TRIGGER_SQL);
        if (definitions.isEmpty()) {
            throw notFound(object);
        }
        return definitions.stream().map(this::terminate).reduce((left, right) -> left + "\n\n" + right)
                .orElseThrow();
    }

    private List<String> queryDefinitions(Connection connection, DatabaseObject object,
                                          String sql) throws SQLException {
        List<String> definitions = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindNamespaceAndName(statement, object);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String definition = rows.getString(1);
                    if (definition != null && !definition.isBlank()) {
                        definitions.add(definition.strip());
                    }
                }
            }
        }
        return definitions;
    }

    private String sqlServerDefinition(Connection connection, DatabaseObject object) throws SQLException {
        if (object.kind() == DatabaseObjectKind.TABLE) {
            return unavailableTableDefinition(object,
                    "SQL Server OBJECT_DEFINITION 不支持表对象",
                    "请使用 SSMS“生成脚本”或 SMO Scripter 获取可还原的权威 DDL");
        }
        if (object.kind() == DatabaseObjectKind.SEQUENCE) {
            throw unsupported(object,
                    "SQL Server 没有返回完整 CREATE SEQUENCE 的内置定义函数，请使用 SSMS 或 SMO Scripter");
        }
        try (PreparedStatement statement = connection.prepareStatement(SQL_SERVER_MODULE_SQL)) {
            statement.setString(1, qualifiedName(object));
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) {
                    throw notFound(object);
                }
                String definition = rows.getString(1);
                if (definition == null || definition.isBlank()) {
                    throw new SQLException("SQL Server 未返回 " + displayName(object)
                            + " 的定义：对象可能不存在，或当前用户缺少 VIEW DEFINITION 权限");
                }
                return definition.strip();
            }
        }
    }

    private void bindNamespaceAndName(PreparedStatement statement,
                                      DatabaseObject object) throws SQLException {
        statement.setString(1, object.schema());
        statement.setString(2, object.name());
    }

    private String unavailableTableDefinition(DatabaseObject object, String reason, String action) {
        return "-- 未生成 " + displayName(object) + " 的 CREATE TABLE：" + reason + "。\n"
                + "-- " + action + "。\n"
                + "-- 为避免遗漏主键、外键、索引、生成列或分区信息，本工具不会从 JDBC 列元数据伪造完整 DDL。";
    }

    private SQLException unsupported(DatabaseObject object, String reason) {
        return new SQLException("无法读取 " + displayName(object) + " 的权威 DDL：" + reason);
    }

    private SQLException notFound(DatabaseObject object) {
        return new SQLException("未找到 " + displayName(object) + " 的 DDL");
    }

    private String qualifiedName(DatabaseObject object) {
        return adapter.quoteIdentifier(object.schema()) + "." + adapter.quoteIdentifier(object.name());
    }

    private String displayName(DatabaseObject object) {
        return qualifiedName(object) + " (" + object.kind().displayName() + ")";
    }

    private String terminate(String sql) {
        String stripped = sql.strip();
        return stripped.endsWith(";") ? stripped : stripped + ";";
    }

    private String readText(ResultSet rows, int column) throws SQLException {
        try (Reader reader = rows.getCharacterStream(column)) {
            if (reader == null) {
                return rows.getString(column);
            }
            StringBuilder result = new StringBuilder();
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) >= 0) {
                result.append(buffer, 0, read);
            }
            return result.toString();
        } catch (IOException exception) {
            throw new SQLException("读取 DDL 文本失败", exception);
        }
    }
}
