package com.dmconnect.query;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.PreviewFilter;

import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Types;

/** Streams a table (or its current preview page) as UTF-8 CSV. */
public final class TableCsvExporter {
    private TableCsvExporter() { }

    public static long write(Connection connection, DatabaseAdapter adapter, DatabaseObject table, PreviewFilter filter,
                             int offset, int maxRows, Path target) throws Exception {
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream().map(item -> adapter.quoteIdentifier(item.column()) + " " + item.operator() + " ?").collect(java.util.stream.Collectors.joining(" AND "));
        Files.createDirectories(target.toAbsolutePath().getParent());
        long written = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + source + where)) {
            adapter.configureStreaming(statement);
            if (filter != null) for (int i = 0; i < filter.conditions().size(); i++) statement.setString(i + 1, filter.conditions().get(i).value());
            if (maxRows > 0) statement.setMaxRows(offset + maxRows + 1);
            try (ResultSet rows = statement.executeQuery(); BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                ResultSetMetaData meta = rows.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) { if (i > 1) writer.write(','); writer.write(CsvExporter.escape(meta.getColumnLabel(i))); }
                writer.newLine();
                for (int skipped = 0; skipped < offset && rows.next(); skipped++) { }
                while (rows.next() && (maxRows <= 0 || written < maxRows)) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) {
                        if (i > 1) writer.write(',');
                        writeCsvValue(writer, rows, meta, i);
                    }
                    writer.newLine(); written++;
                }
            }
        }
        return written;
    }

    private static void writeCsvValue(BufferedWriter writer, ResultSet rows,
                                      ResultSetMetaData metadata, int index) throws Exception {
        if (JdbcValuePolicy.preserveTemporalText(metadata.getColumnTypeName(index))) {
            String value = rows.getString(index);
            if (value != null) writer.write(CsvExporter.escape(value));
            return;
        }
        int jdbcType = metadata.getColumnType(index);
        if (jdbcType == Types.BINARY || jdbcType == Types.VARBINARY
                || jdbcType == Types.LONGVARBINARY || jdbcType == Types.BLOB) {
            try (InputStream input = rows.getBinaryStream(index)) {
                if (input == null) return;
                writer.write("\"0x");
                JdbcStreamWriter.writeHex(writer, input);
                writer.write('"');
            }
            return;
        }
        if (jdbcType == Types.CLOB || jdbcType == Types.NCLOB
                || jdbcType == Types.LONGVARCHAR || jdbcType == Types.LONGNVARCHAR) {
            try (Reader reader = rows.getCharacterStream(index)) {
                if (reader != null) JdbcStreamWriter.writeCsvText(writer, reader);
            }
            return;
        }
        Object value = rows.getObject(index);
        if (value == null) return;
        if (value instanceof byte[] bytes) {
            writer.write("\"0x");
            JdbcStreamWriter.writeHex(writer, bytes);
            writer.write('"');
            return;
        }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                writer.write("\"0x");
                JdbcStreamWriter.writeHex(writer, input);
                writer.write('"');
            } finally {
                try { blob.free(); } catch (SQLException ignored) { }
            }
            return;
        }
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                JdbcStreamWriter.writeCsvText(writer, reader);
            } finally {
                try { clob.free(); } catch (SQLException ignored) { }
            }
            return;
        }
        writer.write(CsvExporter.escape(value.toString()));
    }
}
