package com.dmconnect.database.mysql;

import com.dmconnect.database.spi.MetadataProvider;
import com.dmconnect.model.ColumnInfo;
import com.dmconnect.model.ConstraintInfo;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.IndexInfo;
import com.dmconnect.model.PreviewFilter;
import com.dmconnect.model.TableDetails;
import com.dmconnect.model.TablePreview;
import com.dmconnect.query.ResultSetReader;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/** Reads MySQL metadata using JDBC catalog semantics (a MySQL schema/database is a catalog). */
final class MySqlMetadataProvider implements MetadataProvider {
    private static final String COLUMNS_SQL = """
            SELECT c.ORDINAL_POSITION, c.COLUMN_NAME, c.DATA_TYPE, c.COLUMN_TYPE,
                   c.CHARACTER_MAXIMUM_LENGTH, c.NUMERIC_PRECISION, c.NUMERIC_SCALE,
                   c.DATETIME_PRECISION, c.IS_NULLABLE, c.COLUMN_DEFAULT, c.EXTRA,
                   c.COLUMN_COMMENT, c.GENERATION_EXPRESSION, c.CHARACTER_SET_NAME,
                   c.COLLATION_NAME, c.SRS_ID, t.TABLE_COLLATION
              FROM INFORMATION_SCHEMA.COLUMNS c
              LEFT JOIN INFORMATION_SCHEMA.TABLES t
                ON t.TABLE_SCHEMA = c.TABLE_SCHEMA AND t.TABLE_NAME = c.TABLE_NAME
             WHERE c.TABLE_SCHEMA = ? AND c.TABLE_NAME = ?
             ORDER BY c.ORDINAL_POSITION
            """;
    private static final String LEGACY_COLUMNS_SQL = COLUMNS_SQL.replace("c.SRS_ID", "NULL AS SRS_ID");
    private static final String TRIGGERS_SQL = """
            SELECT TRIGGER_NAME, ACTION_STATEMENT
              FROM INFORMATION_SCHEMA.TRIGGERS
             WHERE TRIGGER_SCHEMA = ?
             ORDER BY TRIGGER_NAME
            """;
    private static final String ROUTINES_SQL = """
            SELECT ROUTINE_NAME, ROUTINE_COMMENT
              FROM INFORMATION_SCHEMA.ROUTINES
             WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = ?
             ORDER BY ROUTINE_NAME
            """;

    private final MySqlDatabaseAdapter adapter;

    MySqlMetadataProvider(MySqlDatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<String> listSchemas(Connection connection) throws SQLException {
        Set<String> catalogs = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (ResultSet rows = connection.getMetaData().getCatalogs()) {
            while (rows.next()) {
                String catalog = rows.getString("TABLE_CAT");
                if (catalog != null && !catalog.isBlank()) catalogs.add(catalog);
            }
        }
        if (catalogs.isEmpty() && connection.getCatalog() != null && !connection.getCatalog().isBlank()) {
            catalogs.add(connection.getCatalog());
        }
        return List.copyOf(catalogs);
    }

    @Override
    public List<DatabaseObject> listObjects(Connection connection, String schema,
                                            DatabaseObjectKind kind) throws SQLException {
        List<DatabaseObject> result = switch (kind) {
            case TABLE -> listTables(connection, schema, "TABLE", kind);
            case VIEW -> listTables(connection, schema, "VIEW", kind);
            case PROCEDURE -> listRoutines(connection, schema, kind);
            case FUNCTION -> listRoutines(connection, schema, kind);
            case TRIGGER -> listTriggers(connection, schema);
            // MySQL has no standalone SEQUENCE object. AUTO_INCREMENT is a column attribute.
            case SEQUENCE -> List.of();
        };
        result.sort(Comparator.comparing(DatabaseObject::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<DatabaseObject> listTables(Connection connection, String catalog, String jdbcType,
                                            DatabaseObjectKind kind) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (ResultSet rows = connection.getMetaData().getTables(catalog, null, "%", new String[]{jdbcType})) {
            while (rows.next()) {
                result.add(new DatabaseObject(catalog, rows.getString("TABLE_NAME"), kind,
                        nullable(rows.getString("REMARKS"))));
            }
        }
        return result;
    }

    private List<DatabaseObject> listRoutines(Connection connection, String catalog,
                                              DatabaseObjectKind kind) throws SQLException {
        // Connector/J may intentionally return functions from getProcedures(), depending on
        // getProceduresReturnsFunctions. INFORMATION_SCHEMA keeps both object kinds unambiguous.
        return listRoutinesFromInformationSchema(connection, catalog, kind);
    }

    private List<DatabaseObject> listRoutinesFromInformationSchema(Connection connection, String catalog,
                                                                   DatabaseObjectKind kind) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ROUTINES_SQL)) {
            statement.setString(1, catalog);
            statement.setString(2, kind == DatabaseObjectKind.PROCEDURE ? "PROCEDURE" : "FUNCTION");
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DatabaseObject(catalog, rows.getString(1), kind,
                            nullable(rows.getString(2))));
                }
            }
        }
        return result;
    }

    private List<DatabaseObject> listTriggers(Connection connection, String catalog) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(TRIGGERS_SQL)) {
            statement.setString(1, catalog);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    result.add(new DatabaseObject(catalog, rows.getString(1), DatabaseObjectKind.TRIGGER,
                            nullable(rows.getString(2))));
                }
            }
        }
        return result;
    }

    @Override
    public TableDetails describeTable(Connection connection, DatabaseObject table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<ConstraintInfo> constraints = new ArrayList<>();
        constraints.addAll(readPrimaryKey(metadata, table));
        constraints.addAll(readForeignKeys(metadata, table));
        return new TableDetails(table, readColumns(connection, table), constraints, readIndexes(metadata, table));
    }

    private List<ColumnInfo> readColumns(Connection connection, DatabaseObject table) throws SQLException {
        try {
            return readColumns(connection, table, COLUMNS_SQL);
        } catch (SQLException modernFailure) {
            // SRS_ID was added after MySQL 5.7. Keep older supported servers readable while
            // treating their spatial columns conservatively through the remaining checks.
            try {
                return readColumns(connection, table, LEGACY_COLUMNS_SQL);
            } catch (SQLException legacyFailure) {
                legacyFailure.addSuppressed(modernFailure);
                throw legacyFailure;
            }
        }
    }

    private List<ColumnInfo> readColumns(Connection connection, DatabaseObject table,
                                         String sql) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table.schema());
            statement.setString(2, table.name());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String dataType = rows.getString("DATA_TYPE").toUpperCase(Locale.ROOT);
                    String columnType = nullable(rows.getString("COLUMN_TYPE"));
                    String extra = nullable(rows.getString("EXTRA"));
                    String generation = nullable(rows.getString("GENERATION_EXPRESSION"));
                    String rawDefault = rows.getString("COLUMN_DEFAULT");
                    String remark = nullable(rows.getString("COLUMN_COMMENT"));
                    String collation = rows.getString("COLLATION_NAME");
                    String tableCollation = rows.getString("TABLE_COLLATION");
                    Long characterLength = nullableLong(rows, "CHARACTER_MAXIMUM_LENGTH");
                    Long numericPrecision = nullableLong(rows, "NUMERIC_PRECISION");
                    Long numericScale = nullableLong(rows, "NUMERIC_SCALE");
                    Long datetimePrecision = nullableLong(rows, "DATETIME_PRECISION");
                    Safety safety = columnSafety(dataType, columnType, extra, generation, rawDefault, remark,
                            rows.getObject("SRS_ID"), collation, tableCollation);
                    String designerType = designerType(dataType, columnType);
                    columns.add(new ColumnInfo(
                            rows.getInt("ORDINAL_POSITION"),
                            rows.getString("COLUMN_NAME"),
                            designerType,
                            safeInt(characterLength != null ? characterLength : numericPrecision),
                            safeInt(numericScale != null ? numericScale : datetimePrecision),
                            "YES".equalsIgnoreCase(rows.getString("IS_NULLABLE")),
                            defaultExpression(dataType, rawDefault),
                            containsWord(extra, "auto_increment"),
                            remark,
                            safety.editable(),
                            safety.warning()));
                }
            }
        }
        return columns;
    }

    private static Safety columnSafety(String dataType, String columnType, String extra,
                                       String generationExpression, String rawDefault, String remark, Object srsId,
                                       String collation, String tableCollation) {
        Set<String> warnings = new LinkedHashSet<>();
        String normalizedColumnType = columnType.toLowerCase(Locale.ROOT);
        String normalizedExtra = extra.toLowerCase(Locale.ROOT);
        if (containsWord(normalizedColumnType, "unsigned")) warnings.add("含 UNSIGNED 属性");
        if (containsWord(normalizedColumnType, "zerofill")) warnings.add("含 ZEROFILL 属性");
        if (!generationExpression.isBlank()
                || normalizedExtra.contains("virtual generated")
                || normalizedExtra.contains("stored generated")) {
            warnings.add("是生成列");
        }
        if (normalizedExtra.contains("default_generated")) warnings.add("含生成式默认值");
        if (rawDefault != null && (rawDefault.indexOf('\\') >= 0
                || rawDefault.chars().anyMatch(Character::isISOControl))) {
            warnings.add("默认值含依赖 SQL 模式的转义字符");
        }
        if (hasUnrecoverableBinaryDefault(dataType, rawDefault)) {
            // INFORMATION_SCHEMA may already have converted or truncated non-printable
            // binary defaults, so rebuilding DDL from COLUMN_DEFAULT is not lossless.
            warnings.add("含无法从元数据无损还原的二进制默认值");
        }
        if (remark.indexOf('\\') >= 0 || remark.chars().anyMatch(Character::isISOControl)) {
            warnings.add("字段备注含依赖 SQL 模式的转义字符");
        }
        if (normalizedExtra.contains("on update")) warnings.add("含 ON UPDATE 属性");
        if (containsWord(normalizedExtra, "invisible")) warnings.add("是不可见列");
        String remainingExtra = normalizedExtra
                .replace("auto_increment", "")
                .replace("default_generated", "")
                .replace("virtual generated", "")
                .replace("stored generated", "")
                .replace("invisible", "")
                .replaceAll("on update\\s+[^ ]+(?:\\([^)]*\\))?", "")
                .strip();
        if (!remainingExtra.isEmpty()) warnings.add("含未建模 EXTRA 属性：" + extra);
        if (srsId != null) warnings.add("含 SRID 空间引用系属性");
        if (collation != null && tableCollation != null && !collation.equalsIgnoreCase(tableCollation)) {
            warnings.add("使用了非表默认字符集或排序规则");
        }
        if (dataType.equals("ENUM") || dataType.equals("SET")) {
            warnings.add("含当前表设计器未建模的枚举值列表");
        }
        if ((dataType.equals("FLOAT") || dataType.equals("DOUBLE") || dataType.equals("REAL"))
                && normalizedColumnType.indexOf('(') >= 0) {
            warnings.add("含浮点精度参数");
        }
        return warnings.isEmpty()
                ? new Safety(true, "")
                : new Safety(false, "为避免丢失 " + String.join("、", warnings) + "，请使用 SQL 工作台修改");
    }

    private static boolean containsWord(String value, String word) {
        String normalized = " " + value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", " ") + " ";
        return normalized.contains(" " + word.toLowerCase(Locale.ROOT) + " ");
    }

    static String defaultExpression(String dataType, String rawDefault) {
        if (rawDefault == null) return null;
        String upper = rawDefault.strip().toUpperCase(Locale.ROOT);
        if ((dataType.equals("TIMESTAMP") || dataType.equals("DATETIME"))
                && upper.matches("CURRENT_TIMESTAMP(?:\\([0-6]\\))?")) {
            return upper;
        }
        if (Set.of("CHAR", "VARCHAR", "TINYTEXT", "TEXT", "MEDIUMTEXT", "LONGTEXT",
                "ENUM", "SET", "DATE", "TIME", "DATETIME", "TIMESTAMP").contains(dataType)) {
            return "'" + rawDefault.replace("\\", "\\\\").replace("'", "''") + "'";
        }
        return rawDefault;
    }

    static String designerType(String dataType, String columnType) {
        return dataType.equals("TINYINT")
                && columnType.toLowerCase(Locale.ROOT).matches("tinyint\\s*\\(\\s*1\\s*\\)")
                ? "BOOLEAN"
                : dataType;
    }

    static boolean hasUnrecoverableBinaryDefault(String dataType, String rawDefault) {
        return rawDefault != null && (dataType.equals("BINARY") || dataType.equals("VARBINARY"));
    }

    private static Long nullableLong(ResultSet rows, String column) throws SQLException {
        long value = rows.getLong(column);
        return rows.wasNull() ? null : value;
    }

    private static int safeInt(Long value) {
        if (value == null || value <= 0) return 0;
        return (int) Math.min(value, Integer.MAX_VALUE);
    }

    private List<ConstraintInfo> readPrimaryKey(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        Map<String, List<OrderedColumn>> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(table.schema(), null, table.name())) {
            while (rows.next()) {
                String name = rows.getString("PK_NAME");
                if (name == null || name.isBlank()) name = "PRIMARY";
                groups.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new OrderedColumn(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME")));
            }
        }
        return groups.entrySet().stream()
                .map(entry -> new ConstraintInfo(entry.getKey(), "PRIMARY KEY", orderedNames(entry.getValue()),
                        null, null, List.of()))
                .toList();
    }

    private List<ConstraintInfo> readForeignKeys(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        record ForeignKeyGroup(String referencedSchema, String referencedTable,
                               List<OrderedColumn> columns, List<OrderedColumn> referencedColumns) {
        }
        Map<String, ForeignKeyGroup> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getImportedKeys(table.schema(), null, table.name())) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                if (name == null || name.isBlank()) name = "FK_" + groups.size();
                String referencedCatalog = rows.getString("PKTABLE_CAT");
                ForeignKeyGroup group = groups.computeIfAbsent(name, ignored -> new ForeignKeyGroup(
                        referencedCatalog, uncheckedString(rows, "PKTABLE_NAME"),
                        new ArrayList<>(), new ArrayList<>()));
                short sequence = rows.getShort("KEY_SEQ");
                group.columns().add(new OrderedColumn(sequence, rows.getString("FKCOLUMN_NAME")));
                group.referencedColumns().add(new OrderedColumn(sequence, rows.getString("PKCOLUMN_NAME")));
            }
        }
        List<ConstraintInfo> result = new ArrayList<>();
        groups.forEach((name, group) -> result.add(new ConstraintInfo(name, "FOREIGN KEY",
                orderedNames(group.columns()), group.referencedSchema(), group.referencedTable(),
                orderedNames(group.referencedColumns()))));
        return result;
    }

    private List<IndexInfo> readIndexes(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        record IndexGroup(boolean unique, List<OrderedColumn> columns) {
        }
        Map<String, IndexGroup> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(table.schema(), null, table.name(), false, true)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                IndexGroup group = groups.computeIfAbsent(name,
                        ignored -> new IndexGroup(!uncheckedBoolean(rows, "NON_UNIQUE"), new ArrayList<>()));
                group.columns().add(new OrderedColumn(rows.getShort("ORDINAL_POSITION"), column));
            }
        }
        return groups.entrySet().stream()
                .map(entry -> new IndexInfo(entry.getKey(), entry.getValue().unique(),
                        orderedNames(entry.getValue().columns())))
                .toList();
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table, int offset,
                                     int maxRows) throws SQLException {
        return previewTable(connection, table, offset, maxRows, null);
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table, int offset,
                                     int maxRows, PreviewFilter filter) throws SQLException {
        if (offset < 0 || maxRows < 1 || maxRows == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数无效");
        }
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream()
                .map(condition -> adapter.quoteIdentifier(condition.column()) + " "
                        + condition.operator() + " ?")
                .collect(Collectors.joining(" AND "));

        long totalRows;
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + source + where)) {
            bindFilter(count, filter, 1);
            try (ResultSet rows = count.executeQuery()) {
                if (!rows.next()) throw new SQLException("数据库未返回表行数");
                totalRows = rows.getLong(1);
            }
        }

        String sql = "SELECT * FROM " + source + where + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int next = bindFilter(statement, filter, 1);
            statement.setInt(next, maxRows + 1);
            statement.setInt(next + 1, offset);
            try (ResultSet rows = statement.executeQuery()) {
                return new TablePreview(ResultSetReader.read(rows, maxRows), totalRows);
            }
        }
    }

    private static int bindFilter(PreparedStatement statement, PreviewFilter filter,
                                  int start) throws SQLException {
        if (filter == null) return start;
        int index = start;
        for (PreviewFilter.Condition condition : filter.conditions()) {
            statement.setString(index++, condition.value());
        }
        return index;
    }

    private static List<String> orderedNames(List<OrderedColumn> columns) {
        return columns.stream().sorted(Comparator.comparingInt(OrderedColumn::sequence))
                .map(OrderedColumn::name).toList();
    }

    private static String nullable(String value) {
        return value == null ? "" : value;
    }

    private static String optionalString(ResultSet rows, String column, String fallback) {
        try {
            return rows.getString(column);
        } catch (SQLException ignored) {
            return fallback;
        }
    }

    private static String uncheckedString(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean uncheckedBoolean(ResultSet rows, String column) {
        try {
            return rows.getBoolean(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record OrderedColumn(int sequence, String name) {
    }

    private record Safety(boolean editable, String warning) {
    }
}
