package com.dmconnect.database.mysql;

import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.Locale;

/** Reads canonical object definitions from MySQL SHOW CREATE statements. */
final class MySqlDdlProvider implements DdlProvider {
    private final MySqlDatabaseAdapter adapter;

    MySqlDdlProvider(MySqlDatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public String getDdl(Connection connection, DatabaseObject object) throws SQLException {
        if (object.kind() == DatabaseObjectKind.SEQUENCE) {
            throw new SQLFeatureNotSupportedException("MySQL 不支持序列对象");
        }
        String qualified = adapter.quoteIdentifier(object.schema()) + "."
                + adapter.quoteIdentifier(object.name());
        String sql = switch (object.kind()) {
            case TABLE -> "SHOW CREATE TABLE " + qualified;
            case VIEW -> "SHOW CREATE VIEW " + qualified;
            case PROCEDURE -> "SHOW CREATE PROCEDURE " + qualified;
            case FUNCTION -> "SHOW CREATE FUNCTION " + qualified;
            case TRIGGER -> "SHOW CREATE TRIGGER " + qualified;
            case SEQUENCE -> throw new AssertionError("handled above");
        };
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            if (!rows.next()) throw new SQLException("数据库未返回对象 DDL");
            String ddl = readCreateValue(rows, object.kind());
            if (ddl == null || ddl.isBlank()) {
                throw new SQLException("当前用户无权查看对象的完整 DDL");
            }
            return ddl;
        }
    }

    private static String readCreateValue(ResultSet rows, DatabaseObjectKind kind) throws SQLException {
        ResultSetMetaData metadata = rows.getMetaData();
        String preferred = switch (kind) {
            case TABLE -> "create table";
            case VIEW -> "create view";
            case PROCEDURE -> "create procedure";
            case FUNCTION -> "create function";
            case TRIGGER -> "sql original statement";
            case SEQUENCE -> "";
        };

        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String label = metadata.getColumnLabel(index);
            if (label != null && label.strip().toLowerCase(Locale.ROOT).equals(preferred)) {
                return value(rows.getObject(index));
            }
        }
        // Connector variants occasionally localize or rename labels. Prefer any CREATE-looking
        // value rather than relying on a brittle fixed ordinal (which differs for routines/triggers).
        for (int index = 1; index <= metadata.getColumnCount(); index++) {
            String value = value(rows.getObject(index));
            if (value != null && value.stripLeading().toUpperCase(Locale.ROOT).startsWith("CREATE ")) {
                return value;
            }
        }
        return null;
    }

    private static String value(Object raw) throws SQLException {
        if (raw instanceof Clob clob) {
            try {
                return clob.getSubString(1, Math.toIntExact(clob.length()));
            } finally {
                try {
                    clob.free();
                } catch (SQLException ignored) {
                    // Some Connector/J-compatible drivers do not implement free().
                }
            }
        }
        return raw == null ? null : raw.toString();
    }
}
