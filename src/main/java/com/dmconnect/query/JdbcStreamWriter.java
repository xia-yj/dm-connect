package com.dmconnect.query;

import com.dmconnect.database.spi.DatabaseAdapter;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/** Bounded-buffer encoders used by large-value exports. */
final class JdbcStreamWriter {
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private JdbcStreamWriter() { }

    static void writeHex(Writer writer, InputStream input) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (read > 0) writeHex(writer, buffer, 0, read);
        }
    }

    static void writeHex(Writer writer, byte[] bytes) throws IOException {
        writeHex(writer, bytes, 0, bytes.length);
    }

    private static void writeHex(Writer writer, byte[] bytes, int offset, int length) throws IOException {
        char[] encoded = new char[length * 2];
        for (int index = 0; index < length; index++) {
            int value = bytes[offset + index] & 0xff;
            encoded[index * 2] = HEX[value >>> 4];
            encoded[index * 2 + 1] = HEX[value & 0x0f];
        }
        writer.write(encoded);
    }

    static void writeSqlTextLiteral(Writer writer, Reader reader, DatabaseAdapter adapter) throws IOException {
        if ("mysql".equalsIgnoreCase(adapter.id())) {
            writer.write("CONVERT(X'");
            OutputStreamWriter utf8 = new OutputStreamWriter(new HexOutputStream(writer), StandardCharsets.UTF_8);
            copy(reader, utf8);
            utf8.flush();
            writer.write("' USING utf8mb4)");
            return;
        }
        writer.write('\'');
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            for (int index = 0; index < read; index++) {
                char value = buffer[index];
                if (value == '\'') writer.write('\'');
                writer.write(value);
            }
        }
        writer.write('\'');
    }

    static void writeCsvText(Writer writer, Reader reader) throws IOException {
        writer.write('"');
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            for (int index = 0; index < read; index++) {
                char value = buffer[index];
                if (value == '"') writer.write('"');
                writer.write(value);
            }
        }
        writer.write('"');
    }

    private static void copy(Reader reader, Writer writer) throws IOException {
        char[] buffer = new char[8192];
        int read;
        while ((read = reader.read(buffer)) >= 0) {
            if (read > 0) writer.write(buffer, 0, read);
        }
    }

    private static final class HexOutputStream extends OutputStream {
        private final Writer writer;

        private HexOutputStream(Writer writer) {
            this.writer = writer;
        }

        @Override
        public void write(int value) throws IOException {
            writer.write(HEX[(value >>> 4) & 0x0f]);
            writer.write(HEX[value & 0x0f]);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeHex(writer, bytes, offset, length);
        }

        @Override
        public void flush() {
            // The enclosing BufferedWriter owns flushing.
        }
    }
}
