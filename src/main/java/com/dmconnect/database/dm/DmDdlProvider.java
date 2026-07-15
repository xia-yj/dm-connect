package com.dmconnect.database.dm;

import com.dmconnect.database.spi.DdlProvider;
import com.dmconnect.model.DatabaseObject;

import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

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
                if (value instanceof Clob clob) {
                    try {
                        return clob.getSubString(1, Math.toIntExact(clob.length()));
                    } finally {
                        try {
                            clob.free();
                        } catch (SQLException ignored) {
                            // 兼容旧驱动。
                        }
                    }
                }
                return value == null ? "" : value.toString();
            }
        }
    }
}
