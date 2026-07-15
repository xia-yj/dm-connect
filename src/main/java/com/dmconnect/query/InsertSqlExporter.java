package com.dmconnect.query;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.PreviewFilter;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.Reader;
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
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;

/** Streams database-specific INSERT statements to a UTF-8 SQL file. */
public final class InsertSqlExporter {
    private InsertSqlExporter() { }

    public static long write(Connection connection, DatabaseAdapter adapter, DatabaseObject table, PreviewFilter filter,
                             int offset, int maxRows, Path target) throws Exception {
        List<String> columns = writableColumns(connection.getMetaData(), adapter, table);
        if (columns.isEmpty()) throw new SQLException("该表仅包含自增字段，无法导出 INSERT 语句");
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream()
                .map(item -> adapter.quoteIdentifier(item.column()) + " " + item.operator() + " ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "SELECT " + columns.stream().map(adapter::quoteIdentifier).collect(java.util.stream.Collectors.joining(", ")) + " FROM " + source + where;
        Files.createDirectories(target.toAbsolutePath().getParent());
        long written = 0;
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            adapter.configureStreaming(statement);
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
                        writeLiteral(writer, rows, metadata, index, adapter);
                    }
                    writer.write(");");
                    writer.newLine();
                    written++;
                }
            }
        }
        return written;
    }

    private static List<String> writableColumns(DatabaseMetaData metadata, DatabaseAdapter adapter, DatabaseObject table) throws SQLException {
        List<String> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(adapter.metadataCatalog(table.schema()), adapter.metadataSchema(table.schema()), table.name(), "%")) {
            while (rows.next()) {
                boolean generated = false;
                try {
                    generated = "YES".equalsIgnoreCase(rows.getString("IS_GENERATEDCOLUMN"));
                } catch (SQLException ignored) {
                    // Older JDBC drivers may not expose generated-column metadata.
                }
                boolean autoIncrement = "YES".equalsIgnoreCase(rows.getString("IS_AUTOINCREMENT"));
                if (isWritableColumn(adapter.id(), generated, autoIncrement)) {
                    columns.add(rows.getString("COLUMN_NAME"));
                }
            }
        }
        return columns;
    }

    static boolean isWritableColumn(String databaseType, boolean generated, boolean autoIncrement) {
        if (generated) return false;
        // MySQL accepts explicit AUTO_INCREMENT values. Keeping them is essential for a
        // restorable export because those values are commonly referenced by foreign keys.
        return "mysql".equalsIgnoreCase(databaseType) || !autoIncrement;
    }

    private static void bindFilter(PreparedStatement statement, PreviewFilter filter) throws SQLException {
        if (filter == null) return;
        for (int index = 0; index < filter.conditions().size(); index++) statement.setString(index + 1, filter.conditions().get(index).value());
    }

    private static void writeLiteral(BufferedWriter writer, ResultSet rows, ResultSetMetaData metadata,
                                     int index, DatabaseAdapter adapter) throws Exception {
        if (JdbcValuePolicy.preserveTemporalText(metadata.getColumnTypeName(index))) {
            writer.write(adapter.stringLiteral(rows.getString(index)));
            return;
        }
        int jdbcType = metadata.getColumnType(index);
        if (jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB) {
            try (InputStream input = rows.getBinaryStream(index)) {
                if (input == null) writer.write("NULL");
                else {
                    writer.write("X'");
                    JdbcStreamWriter.writeHex(writer, input);
                    writer.write('\'');
                }
            }
            return;
        }
        if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB
                || jdbcType == Types.LONGVARCHAR || jdbcType == Types.LONGNVARCHAR) {
            try (Reader reader = rows.getCharacterStream(index)) {
                if (reader == null) writer.write("NULL");
                else JdbcStreamWriter.writeSqlTextLiteral(writer, reader, adapter);
            }
            return;
        }
        Object value = rows.getObject(index);
        if (value == null) { writer.write("NULL"); return; }
        if (value instanceof Boolean bool) { writer.write(bool ? "1" : "0"); return; }
        if (value instanceof Number || value instanceof BigDecimal) { writer.write(value.toString()); return; }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                writer.write("X'");
                JdbcStreamWriter.writeHex(writer, input);
                writer.write('\'');
            } finally {
                try { blob.free(); } catch (SQLException ignored) { }
            }
            return;
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                JdbcStreamWriter.writeSqlTextLiteral(writer, reader, adapter);
            } finally {
                try { clob.free(); } catch (SQLException ignored) { }
            }
            return;
        }
        if (value instanceof byte[] bytes) {
            writer.write("X'");
            JdbcStreamWriter.writeHex(writer, bytes);
            writer.write('\'');
            return;
        }
        writer.write(adapter.stringLiteral(value.toString()));
    }
}
