package com.dmconnect.database.dm;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.backend.RpcException;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds DM DDL for all built-in, conventional DM column data types. */
public final class DmTableDdlBuilder {
    private static final Set<String> TYPES = Set.of(
            "CHAR", "VARCHAR",
            "TINYINT", "BYTE", "SMALLINT", "INT", "INTEGER", "BIGINT", "NUMERIC", "DECIMAL", "DEC", "NUMBER",
            "REAL", "FLOAT", "DOUBLE", "DOUBLE PRECISION",
            "BIT", "BINARY", "VARBINARY",
            "DATE", "TIME", "TIMESTAMP", "DATETIME", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE",
            "INTERVAL YEAR", "INTERVAL MONTH", "INTERVAL YEAR TO MONTH", "INTERVAL DAY", "INTERVAL HOUR", "INTERVAL MINUTE", "INTERVAL SECOND", "INTERVAL DAY TO HOUR", "INTERVAL DAY TO MINUTE", "INTERVAL DAY TO SECOND", "INTERVAL HOUR TO MINUTE", "INTERVAL HOUR TO SECOND", "INTERVAL MINUTE TO SECOND",
            "TEXT", "LONG", "LONGVARCHAR", "IMAGE", "LONGVARBINARY", "BLOB", "CLOB", "BFILE");
    private static final Set<String> LENGTH_TYPES = Set.of("CHAR", "VARCHAR", "BINARY", "VARBINARY");
    private static final Set<String> PRECISION_TYPES = Set.of("NUMERIC", "DECIMAL", "DEC", "NUMBER");
    private static final Set<String> TIME_SCALE_TYPES = Set.of("TIME", "TIMESTAMP", "TIME WITH TIME ZONE", "TIMESTAMP WITH TIME ZONE");
    private final DatabaseAdapter adapter;

    public DmTableDdlBuilder(DatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    public record Column(String originalName, String name, String type, Integer length, Integer scale,
                         boolean nullable, boolean primaryKey, boolean autoIncrement, String defaultExpression,
                         String onUpdateExpression, String remark) { }

    public record Definition(String schema, String name, List<Column> columns, String primaryKeyName) {
        public Definition {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    public String create(Definition definition) {
        validateDefinition(definition, false);
        List<String> parts = new ArrayList<>();
        for (Column column : definition.columns()) parts.add(columnDefinition(column, true));
        List<String> primaryKey = primaryKeys(definition.columns());
        if (!primaryKey.isEmpty()) parts.add("CONSTRAINT " + q(primaryKeyName(definition)) + " PRIMARY KEY (" + joined(primaryKey) + ")");
        return "CREATE TABLE " + table(definition) + " (" + String.join(", ", parts) + ")";
    }

    public List<String> createComments(Definition definition) {
        validateDefinition(definition, false);
        return definition.columns().stream().filter(column -> !normalizedRemark(column.remark()).isEmpty())
                .map(column -> commentStatement(definition, column.name(), column.remark())).toList();
    }

    /** Returns DM ALTER TABLE statements in their required execution order. */
    public List<String> alter(Definition original, Definition target) {
        validateDefinition(original, true);
        validateDefinition(target, false);
        if (!original.schema().equals(target.schema()) || !original.name().equals(target.name())) {
            throw new RpcException("INVALID_ARGUMENT", "编辑表时不能修改所属模式或表名");
        }
        Map<String, Column> oldByName = byName(original.columns());
        Map<String, Column> targetByOriginal = new HashMap<>();
        for (Column column : target.columns()) {
            if (column.originalName() != null && !column.originalName().isBlank()) targetByOriginal.put(key(column.originalName()), column);
        }
        List<String> oldPk = primaryKeys(original.columns());
        List<String> targetPk = primaryKeys(target.columns());
        boolean primaryKeyChanged = !sameIdentifiers(oldPk, targetPk);
        List<String> statements = new ArrayList<>();
        String table = table(target);
        if (primaryKeyChanged && !oldPk.isEmpty()) {
            if (original.primaryKeyName() == null || original.primaryKeyName().isBlank()) {
                throw new RpcException("INVALID_ARGUMENT", "无法识别现有主键约束名称，不能修改主键");
            }
            statements.add("ALTER TABLE " + table + " DROP CONSTRAINT " + q(original.primaryKeyName()) + " CASCADE");
        }
        for (Column old : original.columns()) {
            Column next = targetByOriginal.get(key(old.name()));
            if (next != null && !old.name().equals(next.name())) {
                statements.add("ALTER TABLE " + table + " RENAME COLUMN " + q(old.name()) + " TO " + q(next.name()));
            }
        }
        for (Column old : original.columns()) {
            if (!targetByOriginal.containsKey(key(old.name()))) {
                statements.add("ALTER TABLE " + table + " DROP COLUMN " + q(old.name()) + " CASCADE");
            }
        }
        for (Column next : target.columns()) {
            if (next.originalName() == null || next.originalName().isBlank()) {
                statements.add("ALTER TABLE " + table + " ADD COLUMN " + columnDefinition(next, true));
                if (!normalizedRemark(next.remark()).isEmpty()) statements.add(commentStatement(target, next.name(), next.remark()));
                continue;
            }
            Column old = oldByName.get(key(next.originalName()));
            if (old == null) throw new RpcException("INVALID_ARGUMENT", "找不到原字段：" + next.originalName());
            if (old.autoIncrement() != next.autoIncrement()) {
                throw new RpcException("INVALID_ARGUMENT", "现有字段的自增属性暂不支持图形化修改，请使用 DM SQL 工作台处理");
            }
            if (columnChangedExceptDefault(old, next)) {
                statements.add("ALTER TABLE " + table + " MODIFY " + columnDefinition(next, false));
            }
            String oldDefault = normalizedDefault(old.defaultExpression());
            String newDefault = normalizedDefault(next.defaultExpression());
            if (!oldDefault.equals(newDefault)) {
                statements.add(newDefault.isEmpty()
                        ? "ALTER TABLE " + table + " ALTER COLUMN " + q(next.name()) + " DROP DEFAULT"
                        : "ALTER TABLE " + table + " ALTER COLUMN " + q(next.name()) + " SET DEFAULT " + newDefault);
            }
            if (!normalizedRemark(old.remark()).equals(normalizedRemark(next.remark()))) {
                statements.add(commentStatement(target, next.name(), next.remark()));
            }
        }
        if (primaryKeyChanged && !targetPk.isEmpty()) {
            statements.add("ALTER TABLE " + table + " ADD CONSTRAINT " + q(primaryKeyName(target)) + " PRIMARY KEY (" + joined(targetPk) + ")");
        }
        return List.copyOf(statements);
    }

    private void validateDefinition(Definition definition, boolean original) {
        if (definition == null) throw new RpcException("INVALID_ARGUMENT", "缺少表定义");
        identifier(definition.schema(), "模式名");
        identifier(definition.name(), "表名");
        if (definition.columns().isEmpty()) throw new RpcException("INVALID_ARGUMENT", "表至少需要一个字段");
        Set<String> names = new HashSet<>();
        for (Column column : definition.columns()) {
            identifier(column.name(), "字段名");
            if (!names.add(key(column.name()))) throw new RpcException("INVALID_ARGUMENT", "字段名重复：" + column.name());
            validateColumn(column);
            if (original && (column.originalName() == null || column.originalName().isBlank())) {
                throw new RpcException("INVALID_ARGUMENT", "原表字段缺少原始名称");
            }
        }
    }

    private void validateColumn(Column column) {
        String type = normalizedType(column.type());
        if (!TYPES.contains(type)) throw new RpcException("INVALID_ARGUMENT", "不支持的达梦字段类型：" + column.type());
        if (LENGTH_TYPES.contains(type) && (column.length() == null || column.length() < 1 || column.length() > 32767)) {
            throw new RpcException("INVALID_ARGUMENT", type + " 必须指定 1 到 32767 的长度");
        }
        if (PRECISION_TYPES.contains(type)) {
            if (column.length() == null || column.length() < 1 || column.length() > 38) throw new RpcException("INVALID_ARGUMENT", type + " 精度必须为 1 到 38");
            if (column.scale() != null && (column.scale() < 0 || column.scale() > column.length())) throw new RpcException("INVALID_ARGUMENT", type + " 小数位必须在 0 到精度之间");
        }
        if (TIME_SCALE_TYPES.contains(type) && (column.scale() == null || column.scale() < 0 || column.scale() > 6)) {
            throw new RpcException("INVALID_ARGUMENT", type + " 小数秒精度必须为 0 到 6");
        }
        if (column.autoIncrement() && !type.equals("INT") && !type.equals("BIGINT")) {
            throw new RpcException("INVALID_ARGUMENT", "达梦自增字段仅支持 INT 或 BIGINT");
        }
        if (column.onUpdateExpression() != null && !column.onUpdateExpression().isBlank()) {
            throw new RpcException("INVALID_ARGUMENT", "达梦字段不支持 MySQL ON UPDATE 属性");
        }
        defaultExpression(column.defaultExpression());
        remark(column.remark());
    }

    private String columnDefinition(Column column, boolean includeDefault) {
        String type = normalizedType(column.type());
        StringBuilder value = new StringBuilder(q(column.name())).append(' ').append(type);
        if (LENGTH_TYPES.contains(type)) value.append('(').append(column.length()).append(')');
        if (PRECISION_TYPES.contains(type)) value.append('(').append(column.length()).append(column.scale() == null ? "" : "," + column.scale()).append(')');
        if (type.equals("TIME WITH TIME ZONE")) value = new StringBuilder(q(column.name())).append(" TIME(").append(column.scale()).append(") WITH TIME ZONE");
        else if (type.equals("TIMESTAMP WITH TIME ZONE")) value = new StringBuilder(q(column.name())).append(" TIMESTAMP(").append(column.scale()).append(") WITH TIME ZONE");
        else if (TIME_SCALE_TYPES.contains(type)) value.append('(').append(column.scale()).append(')');
        if (column.autoIncrement()) value.append(" AUTO_INCREMENT");
        if (includeDefault && !normalizedDefault(column.defaultExpression()).isEmpty()) value.append(" DEFAULT ").append(normalizedDefault(column.defaultExpression()));
        if (!column.nullable() || column.primaryKey()) value.append(" NOT NULL");
        return value.toString();
    }

    private String primaryKeyName(Definition definition) {
        if (definition.primaryKeyName() != null && !definition.primaryKeyName().isBlank()) return definition.primaryKeyName();
        String simple = definition.name().replaceAll("[^A-Za-z0-9_]", "_").toUpperCase(Locale.ROOT);
        if (simple.isBlank()) simple = "TABLE";
        String suffix = Integer.toUnsignedString((definition.schema() + "." + definition.name()).hashCode(), 36).toUpperCase(Locale.ROOT);
        return ("PK_" + simple + "_" + suffix).substring(0, Math.min(128, 3 + simple.length() + 1 + suffix.length()));
    }

    private static List<String> primaryKeys(List<Column> columns) { return columns.stream().filter(Column::primaryKey).map(Column::name).toList(); }
    private String joined(List<String> columns) { return columns.stream().map(this::q).collect(java.util.stream.Collectors.joining(", ")); }
    private String table(Definition definition) { return q(definition.schema()) + "." + q(definition.name()); }
    private String q(String value) { return adapter.quoteIdentifier(value); }
    private String commentStatement(Definition definition, String column, String remark) {
        return "COMMENT ON COLUMN " + table(definition) + "." + q(column) + " IS '" + normalizedRemark(remark).replace("'", "''") + "'";
    }
    private static String key(String value) { return value.toUpperCase(Locale.ROOT); }
    private static String normalizedType(String value) { return value == null ? "" : value.strip().toUpperCase(Locale.ROOT); }
    private static String normalizedDefault(String value) { return value == null ? "" : value.strip(); }
    private static String normalizedRemark(String value) { return value == null ? "" : value.strip(); }
    private static Map<String, Column> byName(List<Column> columns) { Map<String, Column> values = new HashMap<>(); for (Column column : columns) values.put(key(column.name()), column); return values; }
    private static boolean sameIdentifiers(List<String> left, List<String> right) { return left.size() == right.size() && java.util.stream.IntStream.range(0, left.size()).allMatch(index -> key(left.get(index)).equals(key(right.get(index)))); }
    private static boolean columnChangedExceptDefault(Column old, Column next) { return !normalizedType(old.type()).equals(normalizedType(next.type())) || !java.util.Objects.equals(old.length(), next.length()) || !java.util.Objects.equals(old.scale(), next.scale()) || old.nullable() != next.nullable(); }
    private static void identifier(String value, String label) { if (value == null || value.isBlank() || value.length() > 128 || value.chars().anyMatch(Character::isISOControl)) throw new RpcException("INVALID_ARGUMENT", label + "无效"); }
    private static void defaultExpression(String value) { String expression = normalizedDefault(value); if (expression.length() > 1000 || expression.contains(";") || expression.contains("--") || expression.contains("/*") || expression.contains("*/")) throw new RpcException("INVALID_ARGUMENT", "默认值表达式无效"); }
    private static void remark(String value) { if (normalizedRemark(value).length() > 1000 || normalizedRemark(value).chars().anyMatch(Character::isISOControl)) throw new RpcException("INVALID_ARGUMENT", "字段备注无效"); }
}
