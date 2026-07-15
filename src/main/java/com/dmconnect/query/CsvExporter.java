package com.dmconnect.query;

import com.dmconnect.model.ResultTable;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class CsvExporter {
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private CsvExporter() {
    }

    public static void write(ResultTable table, Path target) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        try (var output = Files.newOutputStream(target)) {
            output.write(UTF8_BOM);
            try (BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(output,
                    StandardCharsets.UTF_8))) {
                writer.write(table.columns().stream().map(column -> escape(column.label())).reduce((a, b) -> a + "," + b).orElse(""));
                writer.newLine();
                for (var row : table.rows()) {
                    for (int i = 0; i < row.size(); i++) {
                        if (i > 0) writer.write(',');
                        Object value = row.get(i);
                        writer.write(escape(value == null ? "" : value.toString()));
                    }
                    writer.newLine();
                }
            }
        }
    }

    static String escape(String value) {
        boolean quote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0;
        String escaped = value.replace("\"", "\"\"");
        return quote ? "\"" + escaped + "\"" : escaped;
    }
}
