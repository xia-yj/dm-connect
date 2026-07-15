package com.dmconnect.backend;

import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

final class BackendErrorFormatter {
    private static final Pattern SECRET = Pattern.compile(
            "(?i)((?:[a-z0-9_.-]*password[a-z0-9_.-]*|passwd|pwd)\\s*[=:]\\s*)[^\\s,;&]+"
    );

    private BackendErrorFormatter() {
    }

    static String format(Throwable throwable) {
        Set<String> messages = new LinkedHashSet<>();
        String sqlState = "";
        int vendorCode = 0;
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 8; depth++) {
            String message = current.getMessage();
            if (message != null && !message.isBlank()) messages.add(redact(message.strip()));
            if (current instanceof SQLException sql) {
                if (sqlState.isBlank() && sql.getSQLState() != null) sqlState = sql.getSQLState();
                if (vendorCode == 0) vendorCode = sql.getErrorCode();
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        String result = messages.isEmpty() ? throwable.getClass().getSimpleName() : String.join("；原因：", messages);
        if (!sqlState.isBlank() || vendorCode != 0) {
            StringBuilder details = new StringBuilder("（");
            if (!sqlState.isBlank()) details.append("SQLState: ").append(sqlState);
            if (vendorCode != 0) {
                if (!sqlState.isBlank()) details.append("，");
                details.append("数据库错误码: ").append(vendorCode);
            }
            result += details.append("）");
        }
        return result.length() <= 1600 ? result : result.substring(0, 1600) + "…";
    }

    private static String redact(String value) {
        return SECRET.matcher(value).replaceAll("$1***");
    }
}
