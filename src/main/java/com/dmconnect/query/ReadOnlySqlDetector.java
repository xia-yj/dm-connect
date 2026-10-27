package com.dmconnect.query;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 判断语句是否只读，供 MCP 只读模式使用。
 * 宁可误拒也不放行：括号、子查询里的单词同样检查，字符串按最早可能的位置结束。
 */
public final class ReadOnlySqlDetector {
    private static final Set<String> READ_VERBS = Set.of(
            "SELECT", "WITH", "SHOW", "DESC", "DESCRIBE", "EXPLAIN", "VALUES");
    // INTO 覆盖 SELECT ... INTO 建表和 MySQL INTO OUTFILE；UPDATE 顺带拒绝 FOR UPDATE 加锁。
    private static final Set<String> WRITE_WORDS = Set.of(
            "INSERT", "UPDATE", "DELETE", "MERGE", "UPSERT", "INTO", "CREATE", "DROP", "ALTER", "TRUNCATE",
            "RENAME", "GRANT", "REVOKE", "CALL", "EXEC", "EXECUTE", "LOCK", "COPY", "COMMIT", "ROLLBACK",
            "SAVEPOINT", "SET", "LOAD", "HANDLER", "DO");

    public List<String> findWritable(List<String> statements, String databaseType) {
        boolean hashComments = "mysql".equalsIgnoreCase(databaseType);
        return statements.stream().filter(sql -> !isReadOnly(sql, hashComments)).toList();
    }

    public boolean isReadOnly(String sql, boolean hashComments) {
        List<String> words = words(sql, hashComments);
        if (words.isEmpty() || !READ_VERBS.contains(words.get(0))) return false;
        return words.stream().noneMatch(WRITE_WORDS::contains);
    }

    private static List<String> words(String sql, boolean hashComments) {
        List<String> words = new ArrayList<>();
        int index = 0;
        while (index < sql.length()) {
            char current = sql.charAt(index);
            char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';
            if (current == '-' && next == '-') {
                index = skipLine(sql, index + 2);
            } else if (hashComments && current == '#') {
                // 只有 MySQL 把 # 当注释；PostgreSQL 里 # 是按位异或，跳过会漏掉后面的语句。
                index = skipLine(sql, index + 1);
            } else if (current == '/' && next == '*') {
                // MySQL 的 /*! ... */ 会被执行，只跳过开头，内容照常检查。
                if (index + 2 < sql.length() && sql.charAt(index + 2) == '!') index += 3;
                else {
                    int end = sql.indexOf("*/", index + 2);
                    index = end < 0 ? sql.length() : end + 2;
                }
            } else if (current == '\'' || current == '"' || current == '`') {
                index = skipQuoted(sql, index, current);
            } else if (Character.isLetter(current) || current == '_') {
                int start = index;
                index++;
                while (index < sql.length() && (Character.isLetterOrDigit(sql.charAt(index)) || sql.charAt(index) == '_')) index++;
                words.add(sql.substring(start, index).toUpperCase(Locale.ROOT));
            } else {
                index++;
            }
        }
        return words;
    }

    private static int skipLine(String sql, int index) {
        int newline = sql.indexOf('\n', index);
        return newline < 0 ? sql.length() : newline + 1;
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
}
