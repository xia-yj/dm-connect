package com.dmconnect.ui;

import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

import java.util.Collection;
import java.util.Collections;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SyntaxHighlighter {
    private static final String[] KEYWORDS = {
            "SELECT", "FROM", "WHERE", "INSERT", "INTO", "UPDATE", "DELETE", "CREATE", "ALTER",
            "DROP", "TRUNCATE", "TABLE", "VIEW", "INDEX", "SEQUENCE", "TRIGGER", "PROCEDURE",
            "FUNCTION", "PACKAGE", "BEGIN", "END", "DECLARE", "AS", "IS", "JOIN", "LEFT", "RIGHT",
            "INNER", "OUTER", "ON", "GROUP", "ORDER", "BY", "HAVING", "UNION", "ALL", "DISTINCT",
            "VALUES", "SET", "AND", "OR", "NOT", "NULL", "CASE", "WHEN", "THEN", "ELSE", "COMMIT",
            "ROLLBACK", "GRANT", "REVOKE", "PRIMARY", "FOREIGN", "KEY", "CONSTRAINT"
    };
    private static final Pattern PATTERN = Pattern.compile(
            "(?<COMMENT>--[^\\n]*|/\\*(?:.|\\R)*?\\*/)|"
                    + "(?<STRING>'(?:''|[^'])*')|"
                    + "(?<NUMBER>\\b\\d+(?:\\.\\d+)?\\b)|"
                    + "(?<KEYWORD>\\b(?:" + String.join("|", KEYWORDS) + ")\\b)",
            Pattern.CASE_INSENSITIVE);

    private SyntaxHighlighter() {
    }

    static StyleSpans<Collection<String>> compute(String text) {
        Matcher matcher = PATTERN.matcher(text);
        int lastEnd = 0;
        StyleSpansBuilder<Collection<String>> spans = new StyleSpansBuilder<>();
        while (matcher.find()) {
            spans.add(Collections.emptyList(), matcher.start() - lastEnd);
            String style = matcher.group("COMMENT") != null ? "comment"
                    : matcher.group("STRING") != null ? "string"
                    : matcher.group("NUMBER") != null ? "number" : "keyword";
            spans.add(Collections.singleton(style), matcher.end() - matcher.start());
            lastEnd = matcher.end();
        }
        spans.add(Collections.emptyList(), text.length() - lastEnd);
        return spans.create();
    }
}
