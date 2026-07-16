package com.dmconnect.database.dm;

import com.dmconnect.database.spi.MetadataProvider;
import com.dmconnect.model.ColumnInfo;
import com.dmconnect.model.ConstraintInfo;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.IndexInfo;
import com.dmconnect.model.PreviewFilter;
import com.dmconnect.model.TablePreview;
import com.dmconnect.model.TableDetails;
import com.dmconnect.query.ResultSetReader;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class DmMetadataProvider implements MetadataProvider {
    private static final String ALL_OBJECTS_SQL = """
            SELECT OWNER, OBJECT_NAME
              FROM ALL_OBJECTS
             WHERE OWNER = ? AND OBJECT_TYPE = ?
             ORDER BY OBJECT_NAME
            """;
    private final DmDatabaseAdapter adapter;

    DmMetadataProvider(DmDatabaseAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<String> listSchemas(Connection connection) throws SQLException {
        Set<String> schemas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getSchemas()) {
            while (resultSet.next()) {
                String schema = resultSet.getString("TABLE_SCHEM");
                if (schema != null && !schema.isBlank()) schemas.add(schema);
            }
        }
        if (schemas.isEmpty() && metadata.getUserName() != null) schemas.add(metadata.getUserName());
        return List.copyOf(schemas);
    }

    @Override
    public List<DatabaseObject> listObjects(Connection connection, String schema, DatabaseObjectKind kind)
            throws SQLException {
        List<DatabaseObject> result = switch (kind) {
            case TABLE -> listTables(connection, schema, "TABLE", kind);
            case VIEW -> listTables(connection, schema, "VIEW", kind);
            case PROCEDURE -> listProcedures(connection, schema);
            case FUNCTION -> listFunctions(connection, schema);
            case SEQUENCE, TRIGGER -> listFromAllObjects(connection, schema, kind);
        };
        result.sort(Comparator.comparing(DatabaseObject::name, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private List<DatabaseObject> listTables(Connection connection, String schema, String jdbcType,
                                            DatabaseObjectKind kind) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getTables(null, schema, "%", new String[]{jdbcType})) {
            while (rs.next()) {
                result.add(new DatabaseObject(schema, rs.getString("TABLE_NAME"), kind, rs.getString("REMARKS")));
            }
        }
        return result;
    }

    private List<DatabaseObject> listProcedures(Connection connection, String schema) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (ResultSet rs = connection.getMetaData().getProcedures(null, schema, "%")) {
            while (rs.next()) {
                result.add(new DatabaseObject(schema, rs.getString("PROCEDURE_NAME"),
                        DatabaseObjectKind.PROCEDURE, rs.getString("REMARKS")));
            }
        }
        return result.isEmpty() ? listFromAllObjects(connection, schema, DatabaseObjectKind.PROCEDURE) : result;
    }

    private List<DatabaseObject> listFunctions(Connection connection, String schema) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try {
            try (ResultSet rs = connection.getMetaData().getFunctions(null, schema, "%")) {
                while (rs.next()) {
                    result.add(new DatabaseObject(schema, rs.getString("FUNCTION_NAME"),
                            DatabaseObjectKind.FUNCTION, rs.getString("REMARKS")));
                }
            }
        } catch (AbstractMethodError | java.sql.SQLFeatureNotSupportedException ignored) {
            // 旧版 JDBC 驱动可能没有实现 getFunctions，改走达梦字典视图。
        }
        return result.isEmpty() ? listFromAllObjects(connection, schema, DatabaseObjectKind.FUNCTION) : result;
    }

    private List<DatabaseObject> listFromAllObjects(Connection connection, String schema,
                                                    DatabaseObjectKind kind) throws SQLException {
        List<DatabaseObject> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(ALL_OBJECTS_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, kind.ddlType());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    result.add(new DatabaseObject(rs.getString(1), rs.getString(2), kind, ""));
                }
            }
        }
        return result;
    }

    @Override
    public TableDetails describeTable(Connection connection, DatabaseObject table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        List<ColumnInfo> columns = readColumns(metadata, table);
        List<ConstraintInfo> constraints = new ArrayList<>();
        constraints.addAll(readPrimaryKey(metadata, table));
        constraints.addAll(readForeignKeys(metadata, table));
        List<IndexInfo> indexes = readIndexes(metadata, table);
        return new TableDetails(table, columns, constraints, indexes);
    }

    private List<ColumnInfo> readColumns(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rs = metadata.getColumns(null, table.schema(), table.name(), "%")) {
            while (rs.next()) {
                String autoIncrement;
                try {
                    autoIncrement = rs.getString("IS_AUTOINCREMENT");
                } catch (SQLException ignored) {
                    autoIncrement = "NO";
                }
                columns.add(new ColumnInfo(
                        rs.getInt("ORDINAL_POSITION"),
                        rs.getString("COLUMN_NAME"),
                        rs.getString("TYPE_NAME"),
                        rs.getInt("COLUMN_SIZE"),
                        rs.getInt("DECIMAL_DIGITS"),
                        rs.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rs.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(autoIncrement),
                        null,
                        rs.getString("REMARKS"),
                        true,
                        ""));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnInfo::ordinal));
        return columns;
    }

    private List<ConstraintInfo> readPrimaryKey(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        Map<String, List<OrderedColumn>> keys = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getPrimaryKeys(null, table.schema(), table.name())) {
            while (rs.next()) {
                String name = rs.getString("PK_NAME");
                if (name == null || name.isBlank()) name = "PRIMARY";
                keys.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new OrderedColumn(rs.getShort("KEY_SEQ"), rs.getString("COLUMN_NAME")));
            }
        }
        return keys.entrySet().stream()
                .map(entry -> new ConstraintInfo(entry.getKey(), "PRIMARY KEY", orderedNames(entry.getValue()),
                        null, null, List.of()))
                .toList();
    }

    private List<ConstraintInfo> readForeignKeys(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        record ForeignKeyGroup(String referencedSchema, String referencedTable,
                               List<OrderedColumn> columns, List<OrderedColumn> referencedColumns) {
        }
        Map<String, ForeignKeyGroup> keys = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getImportedKeys(null, table.schema(), table.name())) {
            while (rs.next()) {
                String name = rs.getString("FK_NAME");
                if (name == null || name.isBlank()) name = "FK_" + keys.size();
                ForeignKeyGroup group = keys.computeIfAbsent(name, ignored -> new ForeignKeyGroup(
                        rsUnchecked(rs, "PKTABLE_SCHEM"), rsUnchecked(rs, "PKTABLE_NAME"),
                        new ArrayList<>(), new ArrayList<>()));
                short sequence = rs.getShort("KEY_SEQ");
                group.columns().add(new OrderedColumn(sequence, rs.getString("FKCOLUMN_NAME")));
                group.referencedColumns().add(new OrderedColumn(sequence, rs.getString("PKCOLUMN_NAME")));
            }
        }
        List<ConstraintInfo> result = new ArrayList<>();
        keys.forEach((name, group) -> result.add(new ConstraintInfo(name, "FOREIGN KEY",
                orderedNames(group.columns()), group.referencedSchema(), group.referencedTable(),
                orderedNames(group.referencedColumns()))));
        return result;
    }

    private List<IndexInfo> readIndexes(DatabaseMetaData metadata, DatabaseObject table) throws SQLException {
        record IndexGroup(boolean unique, List<OrderedColumn> columns) {
        }
        Map<String, IndexGroup> indexes = new LinkedHashMap<>();
        try (ResultSet rs = metadata.getIndexInfo(null, table.schema(), table.name(), false, true)) {
            while (rs.next()) {
                if (rs.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String name = rs.getString("INDEX_NAME");
                String column = rs.getString("COLUMN_NAME");
                if (name == null || column == null) continue;
                IndexGroup group = indexes.computeIfAbsent(name,
                        ignored -> new IndexGroup(!rsUncheckedBoolean(rs, "NON_UNIQUE"), new ArrayList<>()));
                group.columns().add(new OrderedColumn(rs.getShort("ORDINAL_POSITION"), column));
            }
        }
        return indexes.entrySet().stream()
                .map(entry -> new IndexInfo(entry.getKey(), entry.getValue().unique(),
                        orderedNames(entry.getValue().columns())))
                .toList();
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table, int offset, int maxRows) throws SQLException {
        return previewTable(connection, table, offset, maxRows, null);
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table, int offset, int maxRows,
                                     PreviewFilter filter) throws SQLException {
        if (offset < 0 || maxRows < 1) throw new IllegalArgumentException("分页参数无效");
        String source = adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream()
                .map(condition -> adapter.quoteIdentifier(condition.column()) + " " + condition.operator() + " ?")
                .collect(java.util.stream.Collectors.joining(" AND "));
        String sql = "SELECT * FROM " + source + where;
        long totalRows;
        try (PreparedStatement count = connection.prepareStatement("SELECT COUNT(*) FROM " + source + where)) {
            bindFilter(count, filter);
            try (ResultSet rs = count.executeQuery()) {
                rs.next();
                totalRows = rs.getLong(1);
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindFilter(statement, filter);
            statement.setMaxRows(offset + maxRows + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                for (int skipped = 0; skipped < offset && resultSet.next(); skipped++) {
                    // Keep compatibility with older DM JDBC drivers that only expose forward-only result sets.
                }
                return new TablePreview(ResultSetReader.read(resultSet, maxRows), totalRows);
            }
        }
    }

    private static void bindFilter(PreparedStatement statement, PreviewFilter filter) throws SQLException {
        if (filter == null) return;
        for (int index = 0; index < filter.conditions().size(); index++) {
            statement.setString(index + 1, filter.conditions().get(index).value());
        }
    }

    private static List<String> orderedNames(List<OrderedColumn> columns) {
        return columns.stream().sorted(Comparator.comparingInt(OrderedColumn::sequence))
                .map(OrderedColumn::name).toList();
    }

    private static String rsUnchecked(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static boolean rsUncheckedBoolean(ResultSet rs, String column) {
        try {
            return rs.getBoolean(column);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private record OrderedColumn(int sequence, String name) {
    }
}
