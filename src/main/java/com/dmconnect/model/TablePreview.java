package com.dmconnect.model;

/** A single, counted page of table data used by the object preview. */
public record TablePreview(ResultTable table, long totalRows) {
    public TablePreview {
        if (totalRows < 0) throw new IllegalArgumentException("totalRows must not be negative");
    }
}
