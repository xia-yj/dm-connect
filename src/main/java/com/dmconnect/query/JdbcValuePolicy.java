package com.dmconnect.query;

import java.util.Locale;

/** JDBC values that must be read textually to avoid lossy driver-specific Java mappings. */
final class JdbcValuePolicy {
    private JdbcValuePolicy() { }

    static boolean preserveTemporalText(String typeName) {
        if (typeName == null) return false;
        String type = typeName.strip().toUpperCase(Locale.ROOT);
        int parameters = type.indexOf('(');
        if (parameters >= 0) type = type.substring(0, parameters).strip();
        return type.equals("YEAR") || type.equals("DATE") || type.equals("TIME")
                || type.equals("DATETIME") || type.equals("TIMESTAMP")
                || type.startsWith("TIME WITH ") || type.startsWith("TIMESTAMP WITH ");
    }
}
