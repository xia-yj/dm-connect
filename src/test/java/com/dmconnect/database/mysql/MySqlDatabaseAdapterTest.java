package com.dmconnect.database.mysql;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MySqlDatabaseAdapterTest {
    private final MySqlDatabaseAdapter adapter = new MySqlDatabaseAdapter();

    @Test
    void exposesConnectorJDefaultsAndCatalogMetadataConvention() {
        assertThat(adapter.id()).isEqualTo("mysql");
        assertThat(adapter.displayName()).isEqualTo("MySQL");
        assertThat(adapter.defaultPort()).isEqualTo(3306);
        assertThat(adapter.driverClassName()).isEqualTo("com.mysql.cj.jdbc.Driver");
        assertThat(adapter.metadataCatalog("app_db")).isEqualTo("app_db");
        assertThat(adapter.metadataSchema("app_db")).isNull();
    }

    @Test
    void buildsDeterministicEncodedUrlAndFiltersCredentialAndSemanticOverrides() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("sslMode", "VERIFY_IDENTITY");
        properties.put("connectTimeout", "10 000");
        properties.put("applicationName", "DM Connect 中文");
        properties.put("password", "must-not-leak");
        properties.put("password1", "also-secret");
        properties.put("pwd", "secret-too");
        properties.put("user", "root");
        properties.put("databaseTerm", "SCHEMA");
        properties.put("useAffectedRows", "true");

        assertThat(adapter.buildJdbcUrl("127.0.0.1", 3306, "order db", properties))
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/order%20db"
                        + "?applicationName=DM%20Connect%20%E4%B8%AD%E6%96%87"
                        + "&connectTimeout=10%20000&sslMode=VERIFY_IDENTITY")
                .doesNotContain("must-not-leak", "also-secret", "secret-too", "root", "databaseTerm", "useAffectedRows");
    }

    @Test
    void handlesEmptyDatabaseIpv6AndQuotedIdentifiers() {
        assertThat(adapter.buildJdbcUrl("2001:db8::1", 3306, "", Map.of()))
                .isEqualTo("jdbc:mysql://[2001:db8::1]:3306/");
        assertThat(adapter.quoteIdentifier("odd`name")).isEqualTo("`odd``name`");
    }

    @Test
    void emitsUtf8HexStringLiteralsIndependentOfMysqlSqlMode() {
        assertThat(adapter.stringLiteral("a'b\\\n中"))
                .isEqualTo("CONVERT(X'6127625c0ae4b8ad' USING utf8mb4)");
        assertThat(adapter.stringLiteral("\u0000")).isEqualTo("CONVERT(X'00' USING utf8mb4)");
        assertThat(adapter.stringLiteral(null)).isEqualTo("NULL");
    }

    @Test
    void rejectsMalformedHostPortDatabaseAndIdentifier() {
        assertThatThrownBy(() -> adapter.buildJdbcUrl("bad/host", 3306, "app", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.buildJdbcUrl("localhost", 0, "app", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.buildJdbcUrl("localhost", 3306, "bad\ndb", Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> adapter.quoteIdentifier(""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
