package com.dmconnect.database.dm;

import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.model.DatabaseObject;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class DmDdlProvider implements DdlProvider {
    private static final String SQL = "SELECT DBMS_METADATA.GET_DDL(?, ?, ?)";

    @Override
    public String getDdl(Connection connection, DatabaseObject object) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setString(1, object.kind().ddlType());
            statement.setString(2, object.name());
            statement.setString(3, object.schema());
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) throw new SQLException("数据库未返回对象 DDL");
                Object value = resultSet.getObject(1);
                String ddl;
                if (value instanceof Clob clob) {
                    try {
                        ddl = clob.getSubString(1, Math.toIntExact(clob.length()));
                    } finally {
                        try {
                            clob.free();
                        } catch (SQLException ignored) {
                            // 兼容旧驱动。
                        }
                    }
                } else {
                    ddl = value == null ? "" : value.toString();
                }
                return appendTableComments(connection, object, ddl);
            }
        }
    }

    private static String appendTableComments(Connection connection, DatabaseObject object, String ddl)
            throws SQLException {
        if (object.kind() != com.dmconnect.model.DatabaseObjectKind.TABLE
                || ddl.toUpperCase(Locale.ROOT).contains("COMMENT ON")) return ddl;

        List<String> comments = new ArrayList<>();
        try (ResultSet tables = connection.getMetaData().getTables(null, object.schema(), object.name(), new String[]{"TABLE"})) {
            if (tables.next()) {
                String remark = tables.getString("REMARKS");
                if (hasText(remark)) {
                    comments.add("COMMENT ON TABLE " + qualified(object.schema(), object.name())
                            + " IS " + literal(remark));
                }
            }
        }
        try (ResultSet columns = connection.getMetaData().getColumns(null, object.schema(), object.name(), "%")) {
            while (columns.next()) {
                String remark = columns.getString("REMARKS");
                if (hasText(remark)) {
                    comments.add("COMMENT ON COLUMN " + qualified(object.schema(), object.name()) + "."
                            + quoteIdentifier(columns.getString("COLUMN_NAME")) + " IS " + literal(remark));
                }
            }
        }
        if (comments.isEmpty()) return ddl;
        String separator = ddl.endsWith("\n") ? "\n" : "\n\n";
        return ddl + separator + String.join(";\n", comments) + ";";
    }

    static String qualified(String schema, String name) {
        return quoteIdentifier(schema) + "." + quoteIdentifier(name);
    }

    static String quoteIdentifier(String value) {
        return "\"" + (value == null ? "" : value.replace("\"", "\"\"")) + "\"";
    }

    static String literal(String value) {
        String normalized = value == null ? "" : value.chars()
                .mapToObj(ch -> Character.isISOControl(ch) ? " " : String.valueOf((char) ch))
                .collect(StringBuilder::new, StringBuilder::append, StringBuilder::append)
                .toString();
        return "'" + normalized.replace("'", "''") + "'";
    }

    private static boolean hasText(String value) {
        return value != null && !value.strip().isEmpty();
    }
}
