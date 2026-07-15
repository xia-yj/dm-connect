package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcValuePolicyTest {
    @Test
    void preservesMysqlAndDmTemporalTypesAsDatabaseText() {
        assertThat(JdbcValuePolicy.preserveTemporalText("YEAR")).isTrue();
        assertThat(JdbcValuePolicy.preserveTemporalText("TIME")).isTrue();
        assertThat(JdbcValuePolicy.preserveTemporalText("TIME(6)")).isTrue();
        assertThat(JdbcValuePolicy.preserveTemporalText("DATETIME")).isTrue();
        assertThat(JdbcValuePolicy.preserveTemporalText("TIMESTAMP WITH TIME ZONE")).isTrue();
        assertThat(JdbcValuePolicy.preserveTemporalText("VARCHAR")).isFalse();
        assertThat(JdbcValuePolicy.preserveTemporalText("BIGINT")).isFalse();
    }
}
