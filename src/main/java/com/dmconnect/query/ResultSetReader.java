package com.dmconnect.query;

import com.dmconnect.model.ColumnMetadata;
import com.dmconnect.model.ResultTable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** 将 JDBC 结果集转换为受行数和大对象大小约束的内存结果。 */
public final class ResultSetReader {
    public static final int MAX_TEXT_CHARS = 1_048_576;
    private static final int MAX_BINARY_PREVIEW_BYTES = 256;

    private ResultSetReader() {
    }

    public static ResultTable read(ResultSet resultSet, int maxRows) throws SQLException {
        if (maxRows < 1) throw new IllegalArgumentException("结果行数上限必须大于 0");
        ResultSetMetaData metadata = resultSet.getMetaData();
        List<ColumnMetadata> columns = new ArrayList<>(metadata.getColumnCount());
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            columns.add(new ColumnMetadata(
                    metadata.getColumnName(i),
                    metadata.getColumnLabel(i),
                    metadata.getColumnTypeName(i),
                    metadata.getColumnType(i),
                    metadata.isNullable(i) != ResultSetMetaData.columnNoNulls));
        }

        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(columns.size());
            for (int i = 1; i <= columns.size(); i++) {
                row.add(readValue(resultSet, columns.get(i - 1), i));
            }
            rows.add(row);
        }
        return new ResultTable(columns, rows, truncated);
    }

    private static Object readValue(ResultSet resultSet, ColumnMetadata column, int index) throws SQLException {
        if (JdbcValuePolicy.preserveTemporalText(column.typeName())) return resultSet.getString(index);
        return switch (column.jdbcType()) {
            case Types.CLOB, Types.NCLOB, Types.LONGVARCHAR, Types.LONGNVARCHAR, Types.SQLXML -> {
                try (Reader reader = resultSet.getCharacterStream(index)) {
                    yield reader == null ? null : readLimitedText(reader, "文本");
                } catch (IOException exception) {
                    throw new SQLException("读取大文本字段失败", exception);
                }
            }
            case Types.BINARY, Types.VARBINARY, Types.LONGVARBINARY, Types.BLOB -> {
                try (InputStream input = resultSet.getBinaryStream(index)) {
                    yield input == null ? null : readLimitedBinary(input);
                } catch (IOException exception) {
                    throw new SQLException("读取二进制字段失败", exception);
                }
            }
            default -> safeValue(resultSet.getObject(index));
        };
    }

    private static String readLimitedText(Reader reader, String label) throws IOException {
        char[] buffer = new char[MAX_TEXT_CHARS + 1];
        int total = 0;
        while (total < buffer.length) {
            int read = reader.read(buffer, total, buffer.length - total);
            if (read < 0) break;
            if (read == 0) {
                int value = reader.read();
                if (value < 0) break;
                buffer[total++] = (char) value;
            } else {
                total += read;
            }
        }
        boolean truncated = total > MAX_TEXT_CHARS;
        return new String(buffer, 0, Math.min(total, MAX_TEXT_CHARS))
                + (truncated ? "\n…<" + label + "已截断>" : "");
    }

    private static String readLimitedBinary(InputStream input) throws IOException {
        byte[] preview = input.readNBytes(MAX_BINARY_PREVIEW_BYTES + 1);
        boolean truncated = preview.length > MAX_BINARY_PREVIEW_BYTES;
        int length = Math.min(preview.length, MAX_BINARY_PREVIEW_BYTES);
        return "0x" + HexFormat.of().formatHex(preview, 0, length) + (truncated ? "…<二进制已截断>" : "");
    }

    private static Object safeValue(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                return readLimitedText(reader, "CLOB ");
            } catch (IOException exception) {
                throw new SQLException("读取 CLOB 失败", exception);
            } finally {
                try {
                    clob.free();
                } catch (SQLException ignored) {
                    // 某些旧驱动不支持 free。
                }
            }
        }
        if (value instanceof Blob blob) {
            try (InputStream input = blob.getBinaryStream()) {
                return readLimitedBinary(input);
            } catch (IOException exception) {
                throw new SQLException("读取 BLOB 失败", exception);
            } finally {
                try {
                    blob.free();
                } catch (SQLException ignored) {
                    // 某些旧驱动不支持 free。
                }
            }
        }
        if (value instanceof SQLXML sqlxml) {
            try {
                String text = sqlxml.getString();
                return text.length() <= MAX_TEXT_CHARS
                        ? text
                        : text.substring(0, MAX_TEXT_CHARS) + "\n…<SQLXML 已截断>";
            } finally {
                sqlxml.free();
            }
        }
        if (value instanceof byte[] bytes) {
            int length = Math.min(bytes.length, MAX_BINARY_PREVIEW_BYTES);
            return "0x" + HexFormat.of().formatHex(bytes, 0, length) + (bytes.length > length ? "…" : "");
        }
        if (value instanceof CharSequence chars) {
            String text = chars.toString();
            return text.length() <= MAX_TEXT_CHARS
                    ? text
                    : text.substring(0, MAX_TEXT_CHARS) + "\n…<文本已截断>";
        }
        return value;
    }
}
