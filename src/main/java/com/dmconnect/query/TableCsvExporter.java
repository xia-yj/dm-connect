package com.dmconnect.query;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.PreviewFilter;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;

/** Streams a DM table (or its current preview page) as UTF-8 CSV. */
public final class TableCsvExporter {
    private TableCsvExporter() { }

    public static long write(Connection connection, DatabaseAdapter adapter, DatabaseObject table, PreviewFilter filter,
                             int offset, int maxRows, Path target) throws Exception {
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream().map(item -> adapter.quoteIdentifier(item.column()) + " " + item.operator() + " ?").collect(java.util.stream.Collectors.joining(" AND "));
        Files.createDirectories(target.toAbsolutePath().getParent());
        long written = 0;
        try (PreparedStatement statement = connection.prepareStatement("SELECT * FROM " + source + where)) {
            if (filter != null) for (int i = 0; i < filter.conditions().size(); i++) statement.setString(i + 1, filter.conditions().get(i).value());
            if (maxRows > 0) statement.setMaxRows(offset + maxRows + 1);
            try (ResultSet rows = statement.executeQuery(); BufferedWriter writer = Files.newBufferedWriter(target, StandardCharsets.UTF_8)) {
                ResultSetMetaData meta = rows.getMetaData();
                for (int i = 1; i <= meta.getColumnCount(); i++) { if (i > 1) writer.write(','); writer.write(CsvExporter.escape(meta.getColumnLabel(i))); }
                writer.newLine();
                for (int skipped = 0; skipped < offset && rows.next(); skipped++) { }
                while (rows.next() && (maxRows <= 0 || written < maxRows)) {
                    for (int i = 1; i <= meta.getColumnCount(); i++) { if (i > 1) writer.write(','); Object value = rows.getObject(i); writer.write(CsvExporter.escape(value == null ? "" : value.toString())); }
                    writer.newLine(); written++;
                }
            }
        }
        return written;
    }
}
