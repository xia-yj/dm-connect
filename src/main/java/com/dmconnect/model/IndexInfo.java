package com.dmconnect.model;

import java.util.List;

public record IndexInfo(String name, boolean unique, List<String> columns) {
    public IndexInfo {
        columns = columns == null ? List.of() : List.copyOf(columns);
    }
}
