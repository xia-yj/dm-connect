package com.dmconnect.connection;

import java.sql.Connection;
import java.sql.SQLException;

@FunctionalInterface
public interface JdbcConnectionFactory {
    Connection open() throws SQLException;
}
