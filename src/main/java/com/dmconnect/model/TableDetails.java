package com.dmconnect.model;

import java.util.List;

public record TableDetails(
        DatabaseObject table,
        List<ColumnInfo> columns,
        List<ConstraintInfo> constraints,
        List<IndexInfo> indexes) {
    public TableDetails {
        columns = List.copyOf(columns);
        constraints = List.copyOf(constraints);
        indexes = List.copyOf(indexes);
    }
}
