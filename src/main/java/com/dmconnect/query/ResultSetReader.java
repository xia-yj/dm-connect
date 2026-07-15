package com.dmconnect.query;

import com.dmconnect.model.ColumnMetadata;
import com.dmconnect.model.ResultTable;

import java.io.IOException;
import java.io.Reader;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLXML;
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
                row.add(safeValue(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new ResultTable(columns, rows, truncated);
    }

    private static Object safeValue(Object value) throws SQLException {
        if (value instanceof Clob clob) {
            try (Reader reader = clob.getCharacterStream()) {
                char[] buffer = new char[MAX_TEXT_CHARS + 1];
                int total = 0;
                while (total < buffer.length) {
                    int read = reader.read(buffer, total, buffer.length - total);
                    if (read < 0) break;
                    total += read;
                }
                boolean truncated = total > MAX_TEXT_CHARS || clob.length() > MAX_TEXT_CHARS;
                int length = Math.min(total, MAX_TEXT_CHARS);
                return new String(buffer, 0, length) + (truncated ? "\n…<CLOB 已截断>" : "");
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
            try {
                long length = blob.length();
                int previewLength = (int) Math.min(length, MAX_BINARY_PREVIEW_BYTES);
                byte[] preview = previewLength == 0 ? new byte[0] : blob.getBytes(1, previewLength);
                String suffix = length > previewLength ? "…" : "";
                return "<BLOB " + length + " 字节: " + HexFormat.of().formatHex(preview) + suffix + ">";
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
        return value;
    }
}
