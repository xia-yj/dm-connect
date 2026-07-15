package com.dmconnect.database.dm;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DmDatabaseAdapterTest {
    private final DmDatabaseAdapter adapter = new DmDatabaseAdapter();

    @Test
    void buildsDeterministicUrlWithoutCredentials() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("socketTimeout", "30000");
        properties.put("appName", "DM Connect 中文");
        properties.put("password", "must-not-leak");

        assertThat(adapter.buildJdbcUrl("127.0.0.1", 5236, properties))
                .isEqualTo("jdbc:dm://127.0.0.1:5236?appName=DM%20Connect%20%E4%B8%AD%E6%96%87&socketTimeout=30000")
                .doesNotContain("must-not-leak");
    }

    @Test
    void quotesIdentifiersAndEscapesQuotes() {
        assertThat(adapter.quoteIdentifier("Mixed\"Name")).isEqualTo("\"Mixed\"\"Name\"");
    }

    @Test
    void rejectsInvalidPort() {
        assertThatThrownBy(() -> adapter.buildJdbcUrl("localhost", 0, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
