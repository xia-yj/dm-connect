package com.dmconnect.database.generic;

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
import java.sql.Types;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

final class GenericJdbcMetadata implements MetadataProvider {
    private final GenericJdbcAdapter adapter;

    GenericJdbcMetadata(GenericJdbcAdapter adapter) {
        this.adapter = adapter;
    }

    @Override
    public List<String> listSchemas(Connection connection) throws SQLException {
        TreeSet<String> schemas = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        String currentCatalog = adapter.flavor() == GenericJdbcAdapter.Flavor.SQLSERVER
                ? connection.getCatalog() : null;
        try (ResultSet rows = connection.getMetaData().getSchemas()) {
            while (rows.next()) {
                if (currentCatalog != null) {
                    String rowCatalog = safeString(rows, "TABLE_CATALOG");
                    if (rowCatalog != null && !rowCatalog.equalsIgnoreCase(currentCatalog)) continue;
                }
                String value = rows.getString("TABLE_SCHEM");
                if (value != null && !value.isBlank()) schemas.add(value);
            }
        }
        if (schemas.isEmpty() && adapter.flavor() == GenericJdbcAdapter.Flavor.SQLITE) {
            schemas.add("main");
        }
        if (schemas.isEmpty()) {
            try {
                String current = connection.getSchema();
                if (current != null && !current.isBlank()) schemas.add(current);
            } catch (SQLException | AbstractMethodError ignored) {
                // Older drivers may not implement Connection.getSchema().
            }
        }
        if (schemas.isEmpty()) {
            String user = connection.getMetaData().getUserName();
            if (user != null && !user.isBlank()) schemas.add(user);
        }
        return List.copyOf(schemas);
    }

    @Override
    public List<DatabaseObject> listObjects(Connection connection, String schema,
                                            DatabaseObjectKind kind) throws SQLException {
        if (adapter.flavor() == GenericJdbcAdapter.Flavor.SQLITE
                && (kind == DatabaseObjectKind.PROCEDURE || kind == DatabaseObjectKind.FUNCTION)) {
            return List.of();
        }
        if (kind == DatabaseObjectKind.SEQUENCE || kind == DatabaseObjectKind.TRIGGER) {
            return listVendorObjects(connection, schema, kind);
        }

        Map<String, DatabaseObject> result = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = catalog(connection, schema);
        String metadataSchema = adapter.metadataSchema(schema);
        if (kind == DatabaseObjectKind.TABLE || kind == DatabaseObjectKind.VIEW) {
            String[] tableTypes = kind == DatabaseObjectKind.TABLE ? new String[]{"TABLE"}
                    : adapter.flavor() == GenericJdbcAdapter.Flavor.POSTGRESQL
                    ? new String[]{"VIEW", "MATERIALIZED VIEW"} : new String[]{"VIEW"};
            try (ResultSet rows = metadata.getTables(catalog, metadataSchema, "%", tableTypes)) {
                while (rows.next()) {
                    String name = rows.getString("TABLE_NAME");
                    String actualSchema = Optional.ofNullable(rows.getString("TABLE_SCHEM"))
                            .filter(value -> !value.isBlank()).orElse(schema);
                    result.putIfAbsent(name, new DatabaseObject(actualSchema, name, kind,
                            Optional.ofNullable(rows.getString("REMARKS")).orElse("")));
                }
            }
        } else if (kind == DatabaseObjectKind.PROCEDURE || kind == DatabaseObjectKind.FUNCTION) {
            try (ResultSet rows = kind == DatabaseObjectKind.PROCEDURE
                    ? metadata.getProcedures(catalog, metadataSchema, "%")
                    : metadata.getFunctions(catalog, metadataSchema, "%")) {
                String nameColumn = kind == DatabaseObjectKind.PROCEDURE ? "PROCEDURE_NAME" : "FUNCTION_NAME";
                String schemaColumn = kind == DatabaseObjectKind.PROCEDURE ? "PROCEDURE_SCHEM" : "FUNCTION_SCHEM";
                String catalogColumn = kind == DatabaseObjectKind.PROCEDURE ? "PROCEDURE_CAT" : "FUNCTION_CAT";
                while (rows.next()) {
                    if (adapter.flavor() == GenericJdbcAdapter.Flavor.ORACLE) {
                        String packageName = safeString(rows, catalogColumn);
                        if (packageName != null && !packageName.isBlank()) continue;
                    }
                    String name = rows.getString(nameColumn);
                    String actualSchema = Optional.ofNullable(rows.getString(schemaColumn))
                            .filter(value -> !value.isBlank()).orElse(schema);
                    result.putIfAbsent(name, new DatabaseObject(actualSchema, name, kind,
                            Optional.ofNullable(rows.getString("REMARKS")).orElse("")));
                }
            }
        }
        return List.copyOf(result.values());
    }

    private List<DatabaseObject> listVendorObjects(Connection connection, String schema,
                                                    DatabaseObjectKind kind) throws SQLException {
        if (adapter.flavor() == GenericJdbcAdapter.Flavor.SQLITE && kind == DatabaseObjectKind.SEQUENCE) {
            return List.of();
        }
        String sql = switch (adapter.flavor()) {
            case POSTGRESQL -> kind == DatabaseObjectKind.SEQUENCE ? """
                    SELECT c.relname
                      FROM pg_catalog.pg_class c
                      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = ? AND c.relkind = 'S'
                     ORDER BY c.relname
                    """ : """
                    SELECT DISTINCT t.tgname
                      FROM pg_catalog.pg_trigger t
                      JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                      JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                     WHERE n.nspname = ? AND NOT t.tgisinternal
                     ORDER BY t.tgname
                    """;
            case ORACLE -> kind == DatabaseObjectKind.SEQUENCE
                    ? "SELECT SEQUENCE_NAME FROM ALL_SEQUENCES WHERE SEQUENCE_OWNER = ? ORDER BY SEQUENCE_NAME"
                    : "SELECT TRIGGER_NAME FROM ALL_TRIGGERS WHERE OWNER = ? ORDER BY TRIGGER_NAME";
            case SQLSERVER -> kind == DatabaseObjectKind.SEQUENCE ? """
                    SELECT s.name
                      FROM sys.sequences s
                      JOIN sys.schemas n ON n.schema_id = s.schema_id
                     WHERE n.name = ?
                     ORDER BY s.name
                    """ : """
                    SELECT DISTINCT t.name
                      FROM sys.triggers t
                      JOIN sys.objects p ON p.object_id = t.parent_id
                      JOIN sys.schemas n ON n.schema_id = p.schema_id
                     WHERE n.name = ? AND t.parent_class = 1 AND t.is_ms_shipped = 0
                     ORDER BY t.name
                    """;
            case SQLITE -> "SELECT name FROM " + adapter.quoteIdentifier(schema)
                    + ".sqlite_schema WHERE type = 'trigger' ORDER BY name";
        };
        Map<String, DatabaseObject> objects = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (adapter.flavor() != GenericJdbcAdapter.Flavor.SQLITE) statement.setString(1, schema);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String name = rows.getString(1);
                    if (name != null && !name.isBlank()) {
                        objects.putIfAbsent(name, new DatabaseObject(schema, name, kind, ""));
                    }
                }
            }
        }
        return List.copyOf(objects.values());
    }

    @Override
    public TableDetails describeTable(Connection connection, DatabaseObject table) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        String catalog = catalog(connection, table.schema());
        String schema = adapter.metadataSchema(table.schema());
        List<ColumnInfo> columns = new ArrayList<>();
        try (ResultSet rows = metadata.getColumns(catalog, schema, table.name(), "%")) {
            while (rows.next()) {
                columns.add(new ColumnInfo(rows.getInt("ORDINAL_POSITION"),
                        rows.getString("COLUMN_NAME"), rows.getString("TYPE_NAME"),
                        rows.getInt("COLUMN_SIZE"), rows.getInt("DECIMAL_DIGITS"),
                        rows.getInt("NULLABLE") != DatabaseMetaData.columnNoNulls,
                        rows.getString("COLUMN_DEF"),
                        "YES".equalsIgnoreCase(safeString(rows, "IS_AUTOINCREMENT")),
                        rows.getString("REMARKS"), false,
                        "当前数据库首版请使用 SQL 工作台修改表结构"));
            }
        }
        columns.sort(Comparator.comparingInt(ColumnInfo::ordinal));
        List<ConstraintInfo> constraints = new ArrayList<>(readPrimaryKeys(metadata, catalog, schema, table));
        constraints.addAll(readForeignKeys(metadata, catalog, schema, table));
        return new TableDetails(table, columns, constraints, readIndexes(metadata, catalog, schema, table));
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table,
                                     int offset, int maxRows) throws SQLException {
        return previewTable(connection, table, offset, maxRows, null);
    }

    @Override
    public TablePreview previewTable(Connection connection, DatabaseObject table, int offset,
                                     int maxRows, PreviewFilter filter) throws SQLException {
        if (offset < 0 || maxRows < 1 || maxRows == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("分页参数无效");
        }
        String source = qualifiedName(table);
        String where = filter == null ? "" : " WHERE " + filter.conditions().stream()
                .map(condition -> adapter.quoteIdentifier(condition.column()) + " " + condition.operator() + " ?")
                .collect(Collectors.joining(" AND "));

        long count;
        try (PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + source + where)) {
            bindFilter(statement, filter, 1);
            try (ResultSet rows = statement.executeQuery()) {
                rows.next();
                count = rows.getLong(1);
            }
        }

        boolean offsetFetch = adapter.flavor() == GenericJdbcAdapter.Flavor.ORACLE
                || adapter.flavor() == GenericJdbcAdapter.Flavor.SQLSERVER;
        String orderBy = stableOrderBy(connection, table);
        String sql = offsetFetch
                ? "SELECT * FROM " + source + where + orderBy + " OFFSET ? ROWS FETCH NEXT ? ROWS ONLY"
                : "SELECT * FROM " + source + where + orderBy + " LIMIT ? OFFSET ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int parameter = bindFilter(statement, filter, 1);
            statement.setInt(parameter++, offsetFetch ? offset : maxRows);
            statement.setInt(parameter, offsetFetch ? maxRows : offset);
            try (ResultSet rows = statement.executeQuery()) {
                return new TablePreview(ResultSetReader.read(rows, maxRows), count);
            }
        }
    }

    private int bindFilter(PreparedStatement statement, PreviewFilter filter, int start) throws SQLException {
        int parameter = start;
        if (filter != null) {
            for (PreviewFilter.Condition condition : filter.conditions()) {
                if (adapter.flavor() == GenericJdbcAdapter.Flavor.POSTGRESQL) {
                    // An untyped PostgreSQL parameter lets the server infer the column type;
                    // binding every filter as VARCHAR breaks numeric/date comparisons.
                    statement.setObject(parameter++, condition.value(), Types.OTHER);
                } else {
                    statement.setString(parameter++, condition.value());
                }
            }
        }
        return parameter;
    }

    private String qualifiedName(DatabaseObject table) {
        if (table.schema() == null || table.schema().isBlank()) return adapter.quoteIdentifier(table.name());
        return adapter.quoteIdentifier(table.schema()) + "." + adapter.quoteIdentifier(table.name());
    }

    private String stableOrderBy(Connection connection, DatabaseObject table) {
        List<String> primaryKey = primaryKeyColumns(connection, table);
        if (!primaryKey.isEmpty()) return orderBy(primaryKey);
        List<String> uniqueKey = uniqueIndexColumns(connection, table);
        if (!uniqueKey.isEmpty()) return orderBy(uniqueKey);
        List<String> sortableColumns = sortableColumns(connection, table);
        if (!sortableColumns.isEmpty()) return orderBy(sortableColumns);
        return adapter.flavor() == GenericJdbcAdapter.Flavor.SQLSERVER ? " ORDER BY (SELECT NULL)" : "";
    }

    private List<String> primaryKeyColumns(Connection connection, DatabaseObject table) {
        TreeMap<Integer, String> columns = new TreeMap<>();
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getPrimaryKeys(catalog(connection, table.schema()),
                    adapter.metadataSchema(table.schema()), table.name())) {
                while (rows.next()) {
                    String name = rows.getString("COLUMN_NAME");
                    if (name != null && !name.isBlank()) columns.put(rows.getInt("KEY_SEQ"), name);
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(columns.values());
    }

    private List<String> uniqueIndexColumns(Connection connection, DatabaseObject table) {
        TreeMap<String, TreeMap<Integer, String>> indexes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getIndexInfo(catalog(connection, table.schema()),
                    adapter.metadataSchema(table.schema()), table.name(), true, false)) {
                while (rows.next()) {
                    if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                    String index = rows.getString("INDEX_NAME");
                    String column = rows.getString("COLUMN_NAME");
                    if (index != null && column != null && !column.isBlank()) {
                        indexes.computeIfAbsent(index, ignored -> new TreeMap<>())
                                .put(rows.getInt("ORDINAL_POSITION"), column);
                    }
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return indexes.values().stream().filter(columns -> !columns.isEmpty())
                .map(columns -> List.copyOf(columns.values())).findFirst().orElseGet(List::of);
    }

    private List<String> sortableColumns(Connection connection, DatabaseObject table) {
        TreeMap<Integer, String> columns = new TreeMap<>();
        try {
            DatabaseMetaData metadata = connection.getMetaData();
            try (ResultSet rows = metadata.getColumns(catalog(connection, table.schema()),
                    adapter.metadataSchema(table.schema()), table.name(), "%")) {
                while (rows.next()) {
                    String name = rows.getString("COLUMN_NAME");
                    if (name != null && !name.isBlank() && isSafelySortable(rows.getInt("DATA_TYPE"))) {
                        columns.put(rows.getInt("ORDINAL_POSITION"), name);
                    }
                }
            }
        } catch (SQLException ignored) {
            return List.of();
        }
        return List.copyOf(columns.values());
    }

    private boolean isSafelySortable(int jdbcType) {
        return switch (jdbcType) {
            case Types.BIT, Types.BOOLEAN, Types.TINYINT, Types.SMALLINT, Types.INTEGER,
                    Types.BIGINT, Types.FLOAT, Types.REAL, Types.DOUBLE, Types.NUMERIC,
                    Types.DECIMAL, Types.CHAR, Types.VARCHAR, Types.NCHAR, Types.NVARCHAR,
                    Types.DATE, Types.TIME, Types.TIMESTAMP, Types.TIME_WITH_TIMEZONE,
                    Types.TIMESTAMP_WITH_TIMEZONE, Types.BINARY, Types.VARBINARY -> true;
            default -> false;
        };
    }

    private String orderBy(List<String> columns) {
        return " ORDER BY " + columns.stream().map(adapter::quoteIdentifier)
                .collect(Collectors.joining(", "));
    }

    private List<ConstraintInfo> readPrimaryKeys(DatabaseMetaData metadata, String catalog,
                                                 String schema, DatabaseObject table) throws SQLException {
        Map<String, List<OrderedColumn>> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(catalog, schema, table.name())) {
            while (rows.next()) {
                String name = rows.getString("PK_NAME");
                if (name == null || name.isBlank()) name = "PRIMARY";
                groups.computeIfAbsent(name, ignored -> new ArrayList<>())
                        .add(new OrderedColumn(rows.getInt("KEY_SEQ"), rows.getString("COLUMN_NAME")));
            }
        }
        return groups.entrySet().stream()
                .map(entry -> new ConstraintInfo(entry.getKey(), "PRIMARY KEY", orderedNames(entry.getValue()),
                        null, null, List.of())).toList();
    }

    private List<ConstraintInfo> readForeignKeys(DatabaseMetaData metadata, String catalog,
                                                 String schema, DatabaseObject table) throws SQLException {
        record ForeignKeyGroup(String referencedSchema, String referencedTable,
                               List<OrderedColumn> columns, List<OrderedColumn> referencedColumns) {}
        Map<String, ForeignKeyGroup> groups = new LinkedHashMap<>();
        int anonymousIndex = 0;
        String anonymousName = null;
        try (ResultSet rows = metadata.getImportedKeys(catalog, schema, table.name())) {
            while (rows.next()) {
                String name = rows.getString("FK_NAME");
                int sequence = rows.getInt("KEY_SEQ");
                if (name == null || name.isBlank()) {
                    if (sequence <= 1 || anonymousName == null) anonymousName = "FK_" + anonymousIndex++;
                    name = anonymousName;
                }
                ForeignKeyGroup group = groups.computeIfAbsent(name, ignored -> new ForeignKeyGroup(
                        safeString(rows, "PKTABLE_SCHEM"), safeString(rows, "PKTABLE_NAME"),
                        new ArrayList<>(), new ArrayList<>()));
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

    private List<IndexInfo> readIndexes(DatabaseMetaData metadata, String catalog,
                                        String schema, DatabaseObject table) throws SQLException {
        record IndexGroup(boolean unique, List<OrderedColumn> columns) {}
        Map<String, IndexGroup> groups = new LinkedHashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(catalog, schema, table.name(), false, true)) {
            while (rows.next()) {
                if (rows.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) continue;
                String name = rows.getString("INDEX_NAME");
                String column = rows.getString("COLUMN_NAME");
                if (name == null || column == null || column.isBlank()) continue;
                boolean unique = !rows.getBoolean("NON_UNIQUE");
                IndexGroup group = groups.computeIfAbsent(name,
                        ignored -> new IndexGroup(unique, new ArrayList<>()));
                group.columns().add(new OrderedColumn(rows.getInt("ORDINAL_POSITION"), column));
            }
        }
        return groups.entrySet().stream()
                .map(entry -> new IndexInfo(entry.getKey(), entry.getValue().unique(),
                        orderedNames(entry.getValue().columns()))).toList();
    }

    private String catalog(Connection connection, String namespace) throws SQLException {
        if (adapter.flavor() == GenericJdbcAdapter.Flavor.SQLSERVER) return connection.getCatalog();
        return adapter.metadataCatalog(namespace);
    }

    private static String safeString(ResultSet rows, String column) {
        try {
            return rows.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private static List<String> orderedNames(List<OrderedColumn> columns) {
        return columns.stream().sorted(Comparator.comparingInt(OrderedColumn::position))
                .map(OrderedColumn::name).toList();
    }

    private record OrderedColumn(int position, String name) {}
}
