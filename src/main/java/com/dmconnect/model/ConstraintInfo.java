package com.dmconnect.model;

import java.util.List;

public record ConstraintInfo(
        String name,
        String type,
        List<String> columns,
        String referencedSchema,
        String referencedTable,
        List<String> referencedColumns) {
    public ConstraintInfo {
        columns = columns == null ? List.of() : List.copyOf(columns);
        referencedColumns = referencedColumns == null ? List.of() : List.copyOf(referencedColumns);
    }
}
