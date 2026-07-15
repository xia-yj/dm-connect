package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InsertSqlExporterTest {
    @Test
    void preservesMysqlAutoIncrementValuesButNeverExportsGeneratedColumns() {
        assertThat(InsertSqlExporter.isWritableColumn("mysql", false, true)).isTrue();
        assertThat(InsertSqlExporter.isWritableColumn("dm", false, true)).isFalse();
        assertThat(InsertSqlExporter.isWritableColumn("mysql", true, false)).isFalse();
        assertThat(InsertSqlExporter.isWritableColumn("mysql", true, true)).isFalse();
    }
}
