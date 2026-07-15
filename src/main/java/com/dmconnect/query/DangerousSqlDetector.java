package com.dmconnect.query;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DangerousSqlDetector {
    private static final Pattern FIRST_KEYWORD = Pattern.compile("^([A-Za-z]+)");

    public List<String> findDangerous(List<String> statements) {
        return statements.stream().filter(this::isDangerous).toList();
    }

    public boolean isDangerous(String sql) {
        String executable = SqlScriptParser.stripLeadingComments(sql);
        Matcher matcher = FIRST_KEYWORD.matcher(executable);
        if (!matcher.find()) return false;
        String keyword = matcher.group(1).toUpperCase(Locale.ROOT);
        return keyword.equals("DROP") || keyword.equals("TRUNCATE");
    }
}
