package com.dmconnect.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class DangerousSqlDetector {
    private static final Set<String> ALWAYS_DANGEROUS = Set.of(
            "DROP", "TRUNCATE", "ALTER", "GRANT", "REVOKE", "RENAME", "MERGE");
    private static final Set<String> DANGEROUS_CREATE_TARGETS = Set.of("USER", "ROLE", "LOGIN", "PROFILE");
    private static final Set<String> DML_VERBS = Set.of("DELETE", "UPDATE");

    public List<String> findDangerous(List<String> statements) {
        return statements.stream().filter(this::isDangerous).toList();
    }

    public boolean isDangerous(String sql) {
        String executable = SqlScriptParser.stripLeadingComments(sql);
        List<Token> tokens = tokenizeTopLevel(executable);
        if (tokens.isEmpty()) return false;

        List<String> words = new ArrayList<>();
        for (Token token : tokens) {
            if (token.kind == Kind.WORD) words.add(token.value);
        }
        if (words.isEmpty()) return false;

        int verbIndex = firstVerbIndex(words);
        if (verbIndex < 0) return false;
        String verb = words.get(verbIndex);
        if (ALWAYS_DANGEROUS.contains(verb)) return true;
        if (verb.equals("CREATE")) {
            String target = nextWord(words, verbIndex + 1);
            if ("OR".equals(target) && "REPLACE".equals(nextWord(words, verbIndex + 2))) {
                target = nextWord(words, verbIndex + 3);
            }
            return DANGEROUS_CREATE_TARGETS.contains(target);
        }
        if (DML_VERBS.contains(verb)) return !hasTopLevelWhereClause(tokens);
        return false;
    }

    private static int firstVerbIndex(List<String> words) {
        for (int index = 0; index < words.size(); index++) {
            String word = words.get(index);
            if (word.equals("WITH")) continue;
            if (ALWAYS_DANGEROUS.contains(word) || DML_VERBS.contains(word) || word.equals("CREATE")) {
                return index;
            }
        }
        return -1;
    }

    private static String nextWord(List<String> words, int index) {
        return index >= 0 && index < words.size() ? words.get(index) : "";
    }

    private static boolean hasTopLevelWhereClause(List<Token> tokens) {
        boolean inSetList = false;
        Token previous = null;
        for (Token token : tokens) {
            if (token.kind == Kind.WORD && token.value.equals("SET")) {
                inSetList = true;
                previous = token;
                continue;
            }
            if (token.kind == Kind.WORD && token.value.equals("WHERE")) {
                if (!inSetList) return true;
                if (previous != null && (previous.isWord("SET") || previous.kind == Kind.COMMA)) {
                    previous = token;
                    continue;
                }
                return true;
            }
            previous = token;
        }
        return false;
    }

    private static List<Token> tokenizeTopLevel(String sql) {
        List<Token> tokens = new ArrayList<>();
        int depth = 0;
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (Character.isWhitespace(current)) {
                index++;
                continue;
            }
            if (current == '-' && next == '-') {
                index = skipLine(sql, index + 2);
                continue;
            }
            if (current == '#') {
                index = skipLine(sql, index + 1);
                continue;
            }
            if (current == '/' && next == '*') {
                if (index + 2 < sql.length() && sql.charAt(index + 2) == '!') {
                    index = skipExecutableCommentPrefix(sql, index + 3);
                    continue;
                }
                index = skipBlockComment(sql, index + 2);
                continue;
            }
            if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(sql, index, current);
                if (depth == 0) tokens.add(new Token(Kind.OTHER, ""));
                continue;
            }
            if (current == '(') {
                depth++;
                index++;
                continue;
            }
            if (current == ')') {
                if (depth > 0) depth--;
                index++;
                continue;
            }
            if (depth > 0) {
                index++;
                continue;
            }
            if (current == ',') {
                tokens.add(new Token(Kind.COMMA, ","));
                index++;
                continue;
            }
            if (isWordStart(current)) {
                int start = index;
                index++;
                while (index < sql.length() && isWordPart(sql.charAt(index))) index++;
                tokens.add(new Token(Kind.WORD, sql.substring(start, index).toUpperCase(Locale.ROOT)));
                continue;
            }
            tokens.add(new Token(Kind.OTHER, String.valueOf(current)));
            index++;
        }
        return tokens;
    }

    private static int skipExecutableCommentPrefix(String sql, int index) {
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
        while (index < sql.length() && Character.isDigit(sql.charAt(index))) index++;
        while (index < sql.length() && Character.isWhitespace(sql.charAt(index))) index++;
        return index;
    }

    private static int skipLine(String sql, int index) {
        int newline = sql.indexOf('\n', index);
        return newline < 0 ? sql.length() : newline + 1;
    }

    private static int skipBlockComment(String sql, int index) {
        int end = sql.indexOf("*/", index);
        return end < 0 ? sql.length() : end + 2;
    }

    private static int skipQuoted(String sql, int index, char quote) {
        int cursor = index + 1;
        while (cursor < sql.length()) {
            if (sql.charAt(cursor) == quote) {
                if (cursor + 1 < sql.length() && sql.charAt(cursor + 1) == quote) {
                    cursor += 2;
                    continue;
                }
                return cursor + 1;
            }
            cursor++;
        }
        return sql.length();
    }

    private static boolean isWordStart(char value) {
        return Character.isLetter(value) || value == '_';
    }

    private static boolean isWordPart(char value) {
        return Character.isLetterOrDigit(value) || value == '_';
    }

    private enum Kind { WORD, COMMA, OTHER }

    private record Token(Kind kind, String value) {
        boolean isWord(String expected) {
            return kind == Kind.WORD && value.equals(expected);
        }
    }
}
