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
        assertThat(MySqlMetadataProvider.defaultExpression("TIMESTAMP", "current_timestamp()"))
                .isEqualTo("CURRENT_TIMESTAMP()");
        assertThat(MySqlMetadataProvider.defaultExpression("INT", "42")).isEqualTo("42");
        assertThat(MySqlMetadataProvider.defaultExpression("VARCHAR", null)).isNull();
    }

    @Test
    void acceptsOnlyLosslessCurrentTimestampGeneratedDefaults() {
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("TIMESTAMP", "CURRENT_TIMESTAMP")).isTrue();
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("TIMESTAMP", "current_timestamp()")).isTrue();
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("DATETIME", "current_timestamp(6)")).isTrue();
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("TIMESTAMP", "(uuid())")).isFalse();
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("VARCHAR", "CURRENT_TIMESTAMP")).isFalse();
        assertThat(MySqlMetadataProvider.isSupportedGeneratedDefault("TIMESTAMP", null)).isFalse();
    }

    @Test
    void extractsLosslessOnUpdateCurrentTimestampExpressions() {
        assertThat(MySqlMetadataProvider.onUpdateExpression("DEFAULT_GENERATED on update CURRENT_TIMESTAMP"))
                .isEqualTo("CURRENT_TIMESTAMP");
        assertThat(MySqlMetadataProvider.onUpdateExpression("default_generated ON UPDATE current_timestamp(6)"))
                .isEqualTo("CURRENT_TIMESTAMP(6)");
        assertThat(MySqlMetadataProvider.onUpdateExpression("on update uuid()")).isNull();
        assertThat(MySqlMetadataProvider.onUpdateExpression("auto_increment")).isNull();
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
