package com.dmconnect.database.spi;

import com.dmconnect.model.DatabaseObject;

import java.sql.Connection;
import java.sql.SQLException;

public interface DdlProvider {
    String getDdl(Connection connection, DatabaseObject object) throws SQLException;
}
