package com.dmconnect.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 轻量 SQL 脚本分割器。普通语句以分号结束；过程、函数、触发器、包及匿名块以独占一行的 / 结束。
 */
public final class SqlScriptParser {
    private static final Pattern PROCEDURAL_PREFIX = Pattern.compile(
            "(?is)^(?:CREATE\\s+(?:OR\\s+REPLACE\\s+)?(?:PROCEDURE|FUNCTION|TRIGGER|PACKAGE(?:\\s+BODY)?|TYPE(?:\\s+BODY)?)|DECLARE\\b|BEGIN\\b).*");
    private static final Pattern MYSQL_DELIMITER = Pattern.compile("(?i)^DELIMITER\\s+(\\S+)\\s*$");

    public List<String> split(String source) {
        return splitSegments(source).stream().map(ParsedStatement::sql).toList();
    }

    public List<String> split(String source, String databaseType) {
        return split(source, databaseType, true);
    }

    public List<String> split(String source, String databaseType, boolean mysqlBackslashEscapes) {
        return split(source, databaseType, mysqlBackslashEscapes, false);
    }

    public List<String> split(String source, String databaseType, boolean mysqlBackslashEscapes,
                              boolean mysqlAnsiQuotes) {
        return segments(source, databaseType, mysqlBackslashEscapes, mysqlAnsiQuotes)
                .stream().map(ParsedStatement::sql).toList();
    }

    public List<ParsedStatement> splitSegments(String source) {
        if (source == null || source.isBlank()) return List.of();
        List<ParsedStatement> result = new ArrayList<>();
        State state = State.NORMAL;
        int segmentStart = 0;
        boolean procedural = false;
        int i = 0;
        while (i < source.length()) {
            if (state == State.NORMAL && procedural && isLineStart(source, i)) {
                int lineEnd = source.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = source.length();
                String line = source.substring(i, lineEnd).replace("\r", "").strip();
                if (line.equals("/")) {
                    addSegment(result, source, segmentStart, i);
                    i = lineEnd < source.length() ? lineEnd + 1 : lineEnd;
                    segmentStart = i;
                    procedural = false;
                    continue;
                }
            }

            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-') {
                        state = State.LINE_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (current == '\'') {
                        state = State.SINGLE_QUOTE;
                    } else if (current == '"') {
                        state = State.DOUBLE_QUOTE;
                    } else if (current == ';') {
                        procedural = procedural || isProcedural(source.substring(segmentStart, i));
                        if (!procedural) {
                            addSegment(result, source, segmentStart, i);
                            segmentStart = i + 1;
                        }
                    }
                }
                case SINGLE_QUOTE -> {
                    if (current == '\'' && next == '\'') {
                        i += 2;
                        continue;
                    }
                    if (current == '\'') state = State.NORMAL;
                }
                case DOUBLE_QUOTE -> {
                    if (current == '"' && next == '"') {
                        i += 2;
                        continue;
                    }
                    if (current == '"') state = State.NORMAL;
                }
                case BACKTICK -> {
                    if (current == '`' && next == '`') {
                        i += 2;
                        continue;
                    }
                    if (current == '`') state = State.NORMAL;
                }
                case LINE_COMMENT -> {
                    if (current == '\n') state = State.NORMAL;
                }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') {
                        state = State.NORMAL;
                        i += 2;
                        continue;
                    }
                }
            }
            i++;
        }
        addSegment(result, source, segmentStart, source.length());
        return List.copyOf(result);
    }

    public Optional<ParsedStatement> currentStatement(String source, int caretOffset) {
        return currentStatement(source, caretOffset, "dm");
    }

    public Optional<ParsedStatement> currentStatement(String source, int caretOffset, String databaseType) {
        return currentStatement(source, caretOffset, databaseType, true);
    }

    public Optional<ParsedStatement> currentStatement(String source, int caretOffset, String databaseType,
                                                       boolean mysqlBackslashEscapes) {
        return currentStatement(source, caretOffset, databaseType, mysqlBackslashEscapes, false);
    }

    public Optional<ParsedStatement> currentStatement(String source, int caretOffset, String databaseType,
                                                       boolean mysqlBackslashEscapes, boolean mysqlAnsiQuotes) {
        List<ParsedStatement> statements = segments(source, databaseType, mysqlBackslashEscapes, mysqlAnsiQuotes);
        if (statements.isEmpty()) return Optional.empty();
        int caret = Math.max(0, Math.min(caretOffset, source.length()));
        for (ParsedStatement statement : statements) {
            if (caret >= statement.startOffset() && caret <= statement.endOffset()) return Optional.of(statement);
        }
        return statements.stream()
                .filter(statement -> statement.endOffset() < caret)
                .reduce((first, second) -> second)
                .or(() -> statements.stream().findFirst());
    }

    private List<ParsedStatement> segments(String source, String databaseType, boolean mysqlBackslashEscapes,
                                           boolean mysqlAnsiQuotes) {
        return "mysql".equalsIgnoreCase(databaseType)
                ? splitMySqlSegments(source, mysqlBackslashEscapes, mysqlAnsiQuotes)
                : splitSegments(source);
    }

    /** Parses scripts accepted by the mysql client, including DELIMITER blocks. */
    private List<ParsedStatement> splitMySqlSegments(String source, boolean backslashEscapes,
                                                      boolean ansiQuotes) {
        if (source == null || source.isBlank()) return List.of();
        List<ParsedStatement> result = new ArrayList<>();
        State state = State.NORMAL;
        String delimiter = ";";
        int segmentStart = 0;
        int i = 0;
        while (i < source.length()) {
            if (state == State.NORMAL && isLineStart(source, i)) {
                int lineEnd = source.indexOf('\n', i);
                if (lineEnd < 0) lineEnd = source.length();
                var matcher = MYSQL_DELIMITER.matcher(source.substring(i, lineEnd).replace("\r", "").strip());
                if (matcher.matches()) {
                    addSegment(result, source, segmentStart, i);
                    delimiter = matcher.group(1);
                    i = lineEnd < source.length() ? lineEnd + 1 : lineEnd;
                    segmentStart = i;
                    continue;
                }
            }

            char current = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            switch (state) {
                case NORMAL -> {
                    if (current == '-' && next == '-' && isMySqlDashComment(source, i)) {
                        state = State.LINE_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (current == '#') {
                        state = State.LINE_COMMENT;
                        i++;
                        continue;
                    }
                    if (current == '/' && next == '*') {
                        state = State.BLOCK_COMMENT;
                        i += 2;
                        continue;
                    }
                    if (current == '\'') state = State.SINGLE_QUOTE;
                    else if (current == '"') state = State.DOUBLE_QUOTE;
                    else if (current == '`') state = State.BACKTICK;
                    else if (source.startsWith(delimiter, i)) {
                        addSegment(result, source, segmentStart, i);
                        i += delimiter.length();
                        segmentStart = i;
                        continue;
                    }
                }
                case SINGLE_QUOTE -> {
                    if (backslashEscapes && current == '\\' && i + 1 < source.length()) { i += 2; continue; }
                    if (current == '\'' && next == '\'') { i += 2; continue; }
                    if (current == '\'') state = State.NORMAL;
                }
                case DOUBLE_QUOTE -> {
                    if (backslashEscapes && !ansiQuotes && current == '\\' && i + 1 < source.length()) { i += 2; continue; }
                    if (current == '"' && next == '"') { i += 2; continue; }
                    if (current == '"') state = State.NORMAL;
                }
                case BACKTICK -> {
                    if (current == '`' && next == '`') { i += 2; continue; }
                    if (current == '`') state = State.NORMAL;
                }
                case LINE_COMMENT -> { if (current == '\n') state = State.NORMAL; }
                case BLOCK_COMMENT -> {
                    if (current == '*' && next == '/') { state = State.NORMAL; i += 2; continue; }
                }
            }
            i++;
        }
        addSegment(result, source, segmentStart, source.length());
        return List.copyOf(result);
    }

    static String stripLeadingComments(String sql) {
        String remaining = sql == null ? "" : sql;
        boolean changed;
        do {
            changed = false;
            remaining = remaining.stripLeading();
            if (remaining.startsWith("--")) {
                int newline = remaining.indexOf('\n');
                remaining = newline < 0 ? "" : remaining.substring(newline + 1);
                changed = true;
            } else if (remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                remaining = newline < 0 ? "" : remaining.substring(newline + 1);
                changed = true;
            } else if (remaining.startsWith("/*") && !remaining.startsWith("/*!")) {
                int end = remaining.indexOf("*/", 2);
                if (end < 0) return "";
                remaining = remaining.substring(end + 2);
                changed = true;
            }
        } while (changed);
        return remaining.stripLeading();
    }

    private static boolean isProcedural(String sql) {
        return PROCEDURAL_PREFIX.matcher(stripLeadingComments(sql)).matches();
    }

    private static boolean isLineStart(String source, int index) {
        return index == 0 || source.charAt(index - 1) == '\n';
    }

    private static boolean isMySqlDashComment(String source, int index) {
        int following = index + 2;
        return following >= source.length() || Character.isWhitespace(source.charAt(following))
                || Character.isISOControl(source.charAt(following));
    }

    private static void addSegment(List<ParsedStatement> result, String source, int start, int end) {
        int trimmedStart = start;
        int trimmedEnd = end;
        while (trimmedStart < trimmedEnd && Character.isWhitespace(source.charAt(trimmedStart))) trimmedStart++;
        while (trimmedEnd > trimmedStart && Character.isWhitespace(source.charAt(trimmedEnd - 1))) trimmedEnd--;
        if (trimmedStart >= trimmedEnd) return;
        String text = source.substring(trimmedStart, trimmedEnd);
        if (stripLeadingComments(text).isBlank()) return;
        result.add(new ParsedStatement(text, trimmedStart, trimmedEnd));
    }

    private enum State {
        NORMAL, SINGLE_QUOTE, DOUBLE_QUOTE, BACKTICK, LINE_COMMENT, BLOCK_COMMENT
    }
}
