package com.dmconnect.connection;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

public final class JdbcConnectionHealth {
    private static final int VALIDATION_TIMEOUT_SECONDS = 2;
    public static final long VALIDATION_CACHE_NANOS = 1_000_000_000L;

    private JdbcConnectionHealth() {
    }

    public static boolean isUsable(Connection connection, long lastValidatedNanos) {
        if (connection == null) return false;
        try {
            if (connection.isClosed()) return false;
            if (lastValidatedNanos > 0
                    && System.nanoTime() - lastValidatedNanos < VALIDATION_CACHE_NANOS) return true;
            return connection.isValid(VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) {
            return validationQuery(connection);
        } catch (SQLException exception) {
            return false;
        }
    }

    private static boolean validationQuery(Connection connection) {
        try (Statement statement = connection.createStatement()) {
            try {
                statement.setQueryTimeout(VALIDATION_TIMEOUT_SECONDS);
            } catch (SQLException ignored) {
                // Older JDBC drivers can still execute the portable validation query.
            }
            return statement.execute("SELECT 1");
        } catch (SQLException | AbstractMethodError exception) {
            return false;
        }
    }
}
