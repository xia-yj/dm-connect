package com.dmconnect.database.spi;

import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.PreviewFilter;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.TablePreview;
import com.dmconnect.model.TableDetails;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public interface MetadataProvider {
    List<String> listSchemas(Connection connection) throws SQLException;

    List<DatabaseObject> listObjects(Connection connection, String schema, DatabaseObjectKind kind)
            throws SQLException;

    TableDetails describeTable(Connection connection, DatabaseObject table) throws SQLException;

    TablePreview previewTable(Connection connection, DatabaseObject table, int offset, int maxRows) throws SQLException;

    default TablePreview previewTable(Connection connection, DatabaseObject table, int offset, int maxRows,
                                      PreviewFilter filter) throws SQLException {
        if (filter != null) throw new UnsupportedOperationException("当前数据库不支持数据预览筛选");
        return previewTable(connection, table, offset, maxRows);
    }
}
