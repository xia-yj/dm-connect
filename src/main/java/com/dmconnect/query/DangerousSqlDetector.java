package com.dmconnect.query;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DangerousSqlDetector {
    private static final Pattern FIRST_KEYWORD = Pattern.compile("^([A-Za-z]+)");
    private static final Pattern MYSQL_EXECUTABLE_COMMENT = Pattern.compile(
            "(?is)^/\\*!\\s*(?:[0-9]{5,6}\\s+)?([A-Za-z]+)\\b"
    );

    public List<String> findDangerous(List<String> statements) {
        return statements.stream().filter(this::isDangerous).toList();
    }

    public boolean isDangerous(String sql) {
        String executable = SqlScriptParser.stripLeadingComments(sql);
        Matcher matcher = FIRST_KEYWORD.matcher(executable);
        String keyword;
        if (matcher.find()) {
            keyword = matcher.group(1).toUpperCase(Locale.ROOT);
        } else {
            Matcher executableComment = MYSQL_EXECUTABLE_COMMENT.matcher(executable);
            if (!executableComment.find()) return false;
            keyword = executableComment.group(1).toUpperCase(Locale.ROOT);
        }
        return keyword.equals("DROP") || keyword.equals("TRUNCATE");
    }
}
