package com.dmconnect.query;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.PreviewFilter;

import java.io.BufferedWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Streams DM INSERT statements to a UTF-8 SQL file, excluding auto-increment columns. */
public final class InsertSqlExporter {
    private InsertSqlExporter() { }

    public static long write(Connection connection, DatabaseAdapter adapter, DatabaseObject table, PreviewFilter filter,
                             int offset, int maxRows, Path target) throws Exception {
        List<String> columns = writableColumns(connection.getMetaData(), table);
        if (columns.isEmpty()) throw new SQLException("该表仅包含自增字段，无法导出 INSERT 语句");
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream()
                .map(item -> adapter.quoteIdentifier(item.column()) + " " + item.operator() + " ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "SELECT " + columns.stream().map(adapter::quoteIdentifier).collect(java.util.stream.Collectors.joining(", ")) + " FROM " + source + where;
        Files.createDirectories(target.toAbsolutePath().getParent());
        long written = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilter(statement, filter);
            if (maxRows > 0) statement.setMaxRows(offset + maxRows + 1);
            try (ResultSet rows = statement.executeQuery(); BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                for (int skipped = 0; skipped < offset && rows.next(); skipped++) { }
                String insertPrefix = "INSERT INTO " + source + " (" + columns.stream().map(adapter::quoteIdentifier)
                        .collect(java.util.stream.Collectors.joining(", ")) + ") VALUES (";
                ResultSetMetaData metadata = rows.getMetaData();
                while (rows.next() && (maxRows <= 0 || written < maxRows)) {
                    writer.write(insertPrefix);
                    for (int index = 1; index <= columns.size(); index++) {
                        if (index > 1) writer.write(", ");
                        writer.write(literal(rows.getObject(index), metadata.getColumnType(index)));
                    }
                    writer.write(");");
                    writer.newLine();
                    written++;
                }
            }
        }
        return written;
    }

    private static List<String> writableColumns(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(null, table.schema(), table.name(), "%")) {
            while (rows.next()) if (!"YES".equalsIgnoreCase(rows.getString("IS_AUTOINCREMENT"))) columns.add(rows.getString("COLUMN_NAME"));
        }
        return columns;
    }

    private static void bindFilter(PreparedStatement statement, PreviewFilter filter) throws SQLException {
        if (filter == null) return;
        for (int index = 0; index < filter.conditions().size(); index++) statement.setString(index + 1, filter.conditions().get(index).value());
    }

    private static String literal(Object value, int jdbcType) {
        if (value == null) return "NULL";
        if (value instanceof Number || value instanceof BigDecimal) return value.toString();
        if (value instanceof byte[] bytes) return "X'" + HexFormat.of().formatHex(bytes) + "'";
        return "'" + value.toString().replace("'", "''") + "'";
    }
}
