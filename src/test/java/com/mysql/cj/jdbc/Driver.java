package com.mysql.cj.jdbc;

import java.sql.Connection;
import java.sql.DriverPropertyInfo;
import java.util.Properties;
import java.util.logging.Logger;

/** Minimal Connector/J-shaped driver used to verify isolated JAR discovery in tests. */
public final class Driver implements java.sql.Driver {
    @Override
    public Connection connect(String url, Properties info) {
        return null;
    }

    @Override
    public boolean acceptsURL(String url) {
        return url != null && url.startsWith("jdbc:mysql:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
        return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
        return 9;
    }

    @Override
    public int getMinorVersion() {
        return 7;
    }

    @Override
    public boolean jdbcCompliant() {
        return true;
    }

    @Override
    public Logger getParentLogger() {
        return Logger.getGlobal();
    }
}
