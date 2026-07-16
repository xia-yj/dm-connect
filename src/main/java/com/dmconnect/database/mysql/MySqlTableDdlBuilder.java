package com.dmconnect.database.mysql;

import com.dmconnect.backend.RpcException;
import com.dmconnect.database.dm.DmTableDdlBuilder.Column;
import com.dmconnect.database.dm.DmTableDdlBuilder.Definition;
import com.dmconnect.database.spi.DatabaseAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.IntStream;

/** Builds MySQL 8.x CREATE TABLE and ALTER TABLE statements for the visual table designer. */
public final class MySqlTableDdlBuilder {
    private static final Set<String> TYPES = Set.of(
            "CHAR", "VARCHAR",
            "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT",
            "DECIMAL", "DEC", "NUMERIC", "FIXED",
            "FLOAT", "DOUBLE", "DOUBLE PRECISION", "REAL", "BOOL", "BOOLEAN",
            "BIT", "BINARY", "VARBINARY",
            "DATE", "TIME", "DATETIME", "TIMESTAMP", "YEAR",
            "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
            "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB",
            "JSON", "GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT",
            "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION");
    private static final Set<String> REQUIRED_LENGTH_TYPES = Set.of(
            "CHAR", "VARCHAR", "BINARY", "VARBINARY", "BIT");
    private static final Set<String> PRECISION_TYPES = Set.of(
            "DECIMAL", "DEC", "NUMERIC", "FIXED");
    private static final Set<String> TIME_SCALE_TYPES = Set.of("TIME", "DATETIME", "TIMESTAMP");
    private static final Set<String> AUTO_INCREMENT_TYPES = Set.of(
            "TINYINT", "SMALLINT", "MEDIUMINT", "INT", "INTEGER", "BIGINT");
    private static final Set<String> UNSUPPORTED_PRIMARY_KEY_TYPES = Set.of(
            "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
            "TINYBLOB", "BLOB", "MEDIUMBLOB", "LONGBLOB", "JSON",
            "GEOMETRY", "POINT", "LINESTRING", "POLYGON", "MULTIPOINT",
            "MULTILINESTRING", "MULTIPOLYGON", "GEOMETRYCOLLECTION");

    private final DatabaseAdapter adapter;

    public MySqlTableDdlBuilder(DatabaseAdapter adapter) {
        if (adapter == null || !"mysql".equals(adapter.id())) {
            throw new IllegalArgumentException("MySQL DDL 生成器需要 MySQL 数据库适配器");
        }
        this.adapter = adapter;
    }

    public String create(Definition definition) {
        validateDefinition(definition, false);
        List<String> parts = new ArrayList<>();
        for (Column column : definition.columns()) parts.add(columnDefinition(column));
        List<String> primaryKeys = primaryKeys(definition.columns());
        if (!primaryKeys.isEmpty()) parts.add("PRIMARY KEY (" + joined(primaryKeys) + ")");
        return "CREATE TABLE " + table(definition) + " (" + String.join(", ", parts) + ")";
    }

    /** MySQL stores column comments inline in the column definition. */
    public List<String> createComments(Definition definition) {
        validateDefinition(definition, false);
        return List.of();
    }

    /**
     * Returns at most one ALTER TABLE statement. Combining clauses lets MySQL validate
     * AUTO_INCREMENT and primary-key transitions against the final table definition.
     */
    public List<String> alter(Definition original, Definition target) {
        validateDefinition(original, true);
        validateDefinition(target, false);
        if (!original.schema().equals(target.schema()) || !original.name().equals(target.name())) {
            throw new RpcException("INVALID_ARGUMENT", "编辑表时不能修改所属数据库或表名");
        }

        Map<String, Column> oldByName = byName(original.columns());
        Map<String, Column> targetByOriginal = new HashMap<>();
        for (Column column : target.columns()) {
            if (hasText(column.originalName())) {
                String originalKey = key(column.originalName());
                if (targetByOriginal.put(originalKey, column) != null) {
                    throw new RpcException("INVALID_ARGUMENT", "多个字段引用了同一个原字段：" + column.originalName());
                }
                if (!oldByName.containsKey(originalKey)) {
                    throw new RpcException("INVALID_ARGUMENT", "找不到原字段：" + column.originalName());
                }
            }
        }

        boolean primaryKeyChanged = primaryKeyChanged(original, target);
        List<String> clauses = new ArrayList<>();
        if (primaryKeyChanged && !primaryKeys(original.columns()).isEmpty()) {
            clauses.add("DROP PRIMARY KEY");
        }

        for (Column old : original.columns()) {
            Column next = targetByOriginal.get(key(old.name()));
            if (next == null) clauses.add("DROP COLUMN " + q(old.name()));
        }

        for (Column next : target.columns()) {
            if (!hasText(next.originalName())) {
                clauses.add("ADD COLUMN " + columnDefinition(next));
                continue;
            }
            Column old = oldByName.get(key(next.originalName()));
            if (columnChanged(old, next)) {
                clauses.add("CHANGE COLUMN " + q(old.name()) + " " + columnDefinition(next));
            }
        }

        List<String> targetPrimaryKeys = primaryKeys(target.columns());
        if (primaryKeyChanged && !targetPrimaryKeys.isEmpty()) {
            clauses.add("ADD PRIMARY KEY (" + joined(targetPrimaryKeys) + ")");
        }
        if (clauses.isEmpty()) return List.of();
        return List.of("ALTER TABLE " + table(target) + " " + String.join(", ", clauses));
    }

    private void validateDefinition(Definition definition, boolean original) {
        if (definition == null) throw new RpcException("INVALID_ARGUMENT", "缺少表定义");
        identifier(definition.schema(), "数据库名");
        identifier(definition.name(), "表名");
        if (definition.columns().isEmpty()) {
            throw new RpcException("INVALID_ARGUMENT", "表至少需要一个字段");
        }
        Set<String> names = new HashSet<>();
        List<Column> autoIncrement = new ArrayList<>();
        for (Column column : definition.columns()) {
            identifier(column.name(), "字段名");
            if (!names.add(key(column.name()))) {
                throw new RpcException("INVALID_ARGUMENT", "字段名重复：" + column.name());
            }
            if (original && !hasText(column.originalName())) {
                throw new RpcException("INVALID_ARGUMENT", "原表字段缺少原始名称");
            }
            validateColumn(column);
            if (column.autoIncrement()) autoIncrement.add(column);
        }
        if (autoIncrement.size() > 1) {
            throw new RpcException("INVALID_ARGUMENT", "MySQL 每张表只能有一个 AUTO_INCREMENT 字段");
        }
        if (!autoIncrement.isEmpty()) {
            Column column = autoIncrement.get(0);
            List<String> primary = primaryKeys(definition.columns());
            if (!column.primaryKey() || primary.isEmpty()
                    || !key(primary.get(0)).equals(key(column.name()))) {
                throw new RpcException("INVALID_ARGUMENT",
                        "AUTO_INCREMENT 字段必须是主键的第一个字段");
            }
        }
    }

    private void validateColumn(Column column) {
        String type = normalizedType(column.type());
        if (!TYPES.contains(type)) {
            throw new RpcException("INVALID_ARGUMENT", "不支持的 MySQL 字段类型：" + column.type());
        }
        if (REQUIRED_LENGTH_TYPES.contains(type)) {
            int maximum = switch (type) {
                case "CHAR", "BINARY" -> 255;
                case "BIT" -> 64;
                default -> 65_535;
            };
            if (column.length() == null || column.length() < 1 || column.length() > maximum) {
                throw new RpcException("INVALID_ARGUMENT",
                        type + " 长度必须为 1 到 " + maximum);
            }
        }
        if (PRECISION_TYPES.contains(type)) {
            if (column.length() == null || column.length() < 1 || column.length() > 65) {
                throw new RpcException("INVALID_ARGUMENT", type + " 精度必须为 1 到 65");
            }
            if (column.scale() != null && (column.scale() < 0 || column.scale() > 30
                    || column.scale() > column.length())) {
                throw new RpcException("INVALID_ARGUMENT", type + " 小数位必须在 0 到 30 之间，且不得超过精度");
            }
        }
        if (TIME_SCALE_TYPES.contains(type) && column.scale() != null
                && (column.scale() < 0 || column.scale() > 6)) {
            throw new RpcException("INVALID_ARGUMENT", type + " 小数秒精度必须为 0 到 6");
        }
        if (column.autoIncrement() && !AUTO_INCREMENT_TYPES.contains(type)) {
            throw new RpcException("INVALID_ARGUMENT", "MySQL 自增字段必须使用整数类型");
        }
        if (column.autoIncrement() && hasText(column.defaultExpression())) {
            throw new RpcException("INVALID_ARGUMENT", "AUTO_INCREMENT 字段不能设置默认值");
        }
        if (hasText(column.onUpdateExpression())) {
            if (!TIME_SCALE_TYPES.contains(type) || type.equals("TIME")) {
                throw new RpcException("INVALID_ARGUMENT", "MySQL ON UPDATE 仅支持 DATETIME 或 TIMESTAMP 字段");
            }
            if (!column.onUpdateExpression().strip().toUpperCase(Locale.ROOT)
                    .matches("CURRENT_TIMESTAMP(?:\\([0-6]?\\))?")) {
                throw new RpcException("INVALID_ARGUMENT", "ON UPDATE 仅支持 CURRENT_TIMESTAMP 及 0 到 6 位精度");
            }
        }
        if (column.primaryKey() && UNSUPPORTED_PRIMARY_KEY_TYPES.contains(type)) {
            throw new RpcException("INVALID_ARGUMENT", type + " 不能由当前表设计器直接定义为主键");
        }
        defaultExpression(column.defaultExpression());
        remark(column.remark());
    }

    private String columnDefinition(Column column) {
        String type = normalizedType(column.type());
        StringBuilder value = new StringBuilder(q(column.name())).append(' ').append(type);
        if (REQUIRED_LENGTH_TYPES.contains(type)) value.append('(').append(column.length()).append(')');
        if (PRECISION_TYPES.contains(type)) {
            value.append('(').append(column.length());
            if (column.scale() != null) value.append(',').append(column.scale());
            value.append(')');
        }
        if (TIME_SCALE_TYPES.contains(type) && column.scale() != null) {
            value.append('(').append(column.scale()).append(')');
        }
        if (!column.nullable() || column.primaryKey()) value.append(" NOT NULL");
        if (hasText(column.defaultExpression())) {
            value.append(" DEFAULT ").append(normalizedDefault(column.defaultExpression()));
        }
        if (hasText(column.onUpdateExpression())) {
            value.append(" ON UPDATE ").append(column.onUpdateExpression().strip().toUpperCase(Locale.ROOT));
        }
        if (column.autoIncrement()) value.append(" AUTO_INCREMENT");
        if (hasText(column.remark())) {
            value.append(" COMMENT '").append(normalizedRemark(column.remark())
                    .replace("\\", "\\\\").replace("'", "''")).append('\'');
        }
        return value.toString();
    }

    private boolean primaryKeyChanged(Definition original, Definition target) {
        List<String> oldPrimary = primaryKeys(original.columns()).stream().map(MySqlTableDdlBuilder::key).toList();
        List<String> targetIdentity = target.columns().stream().filter(Column::primaryKey)
                .map(column -> key(hasText(column.originalName()) ? column.originalName() : column.name()))
                .toList();
        return !oldPrimary.equals(targetIdentity);
    }

    private static boolean columnChanged(Column old, Column next) {
        return !old.name().equals(next.name())
                || !normalizedType(old.type()).equals(normalizedType(next.type()))
                || !Objects.equals(old.length(), next.length())
                || !Objects.equals(old.scale(), next.scale())
                || old.nullable() != next.nullable()
                || old.primaryKey() != next.primaryKey()
                || old.autoIncrement() != next.autoIncrement()
                || !normalizedDefault(old.defaultExpression()).equals(normalizedDefault(next.defaultExpression()))
                || !normalizedDefault(old.onUpdateExpression()).equals(normalizedDefault(next.onUpdateExpression()))
                || !normalizedRemark(old.remark()).equals(normalizedRemark(next.remark()));
    }

    private String table(Definition definition) {
        return q(definition.schema()) + "." + q(definition.name());
    }

    private String joined(List<String> columns) {
        return columns.stream().map(this::q).collect(java.util.stream.Collectors.joining(", "));
    }

    private String q(String value) {
        return adapter.quoteIdentifier(value);
    }

    private static List<String> primaryKeys(List<Column> columns) {
        return columns.stream().filter(Column::primaryKey).map(Column::name).toList();
    }

    private static Map<String, Column> byName(List<Column> columns) {
        Map<String, Column> values = new HashMap<>();
        for (Column column : columns) values.put(key(column.name()), column);
        return values;
    }

    private static String key(String value) {
        return value.toUpperCase(Locale.ROOT);
    }

    private static String normalizedType(String value) {
        return value == null ? "" : value.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    private static String normalizedDefault(String value) {
        return value == null ? "" : value.strip();
    }

    private static String normalizedRemark(String value) {
        return value == null ? "" : value.strip();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static void identifier(String value, String label) {
        if (!hasText(value) || value.length() > 64 || value.chars().anyMatch(Character::isISOControl)) {
            throw new RpcException("INVALID_ARGUMENT", label + "无效");
        }
    }

    private static void defaultExpression(String value) {
        String expression = normalizedDefault(value);
        if (expression.length() > 1000 || expression.contains(";") || expression.contains("--")
                || expression.contains("/*") || expression.contains("*/")) {
            throw new RpcException("INVALID_ARGUMENT", "默认值表达式无效");
        }
    }

    private static void remark(String value) {
        String remark = normalizedRemark(value);
        if (remark.length() > 1024 || remark.indexOf('\\') >= 0
                || remark.chars().anyMatch(Character::isISOControl)) {
            throw new RpcException("INVALID_ARGUMENT", "字段备注无效");
        }
    }
}
