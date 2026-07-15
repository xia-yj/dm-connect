package com.dmconnect.database.mysql;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlMetadataProviderTest {
    @Test
    void reconstructsLiteralDefaultsForSafeDesignerRoundTrips() {
        assertThat(MySqlMetadataProvider.defaultExpression("VARCHAR", "O'Reilly"))
                .isEqualTo("'O''Reilly'");
        assertThat(MySqlMetadataProvider.defaultExpression("VARCHAR", "C:\\tmp"))
                .isEqualTo("'C:\\\\tmp'");
        assertThat(MySqlMetadataProvider.defaultExpression("DATE", "2026-07-15"))
                .isEqualTo("'2026-07-15'");
        assertThat(MySqlMetadataProvider.defaultExpression("TIMESTAMP", "current_timestamp(6)"))
                .isEqualTo("CURRENT_TIMESTAMP(6)");
        assertThat(MySqlMetadataProvider.defaultExpression("INT", "42")).isEqualTo("42");
        assertThat(MySqlMetadataProvider.defaultExpression("VARCHAR", null)).isNull();
    }

    @Test
    void preservesConnectorJBooleanMappingForTinyintOne() {
        assertThat(MySqlMetadataProvider.designerType("TINYINT", "tinyint(1)")).isEqualTo("BOOLEAN");
        assertThat(MySqlMetadataProvider.designerType("TINYINT", "tinyint(4)")).isEqualTo("TINYINT");
        assertThat(MySqlMetadataProvider.designerType("INT", "int(1)")).isEqualTo("INT");
    }

    @Test
    void blocksBinaryDefaultsThatInformationSchemaCannotRoundTripLosslessly() {
        assertThat(MySqlMetadataProvider.hasUnrecoverableBinaryDefault("BINARY", "value")).isTrue();
        assertThat(MySqlMetadataProvider.hasUnrecoverableBinaryDefault("VARBINARY", "")).isTrue();
        assertThat(MySqlMetadataProvider.hasUnrecoverableBinaryDefault("VARBINARY", null)).isFalse();
        assertThat(MySqlMetadataProvider.hasUnrecoverableBinaryDefault("VARCHAR", "value")).isFalse();
    }
}
