package com.dmconnect.model;

import java.util.Objects;

public record DatabaseObject(String schema, String name, DatabaseObjectKind kind, String remarks) {
    public DatabaseObject {
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        remarks = remarks == null ? "" : remarks;
    }

    @Override
    public String toString() {
        return name;
    }
}
