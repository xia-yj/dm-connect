package com.dmconnect.database.dm;

import com.dmconnect.backend.RpcException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DmTableDdlBuilderTest {
    private final DmTableDdlBuilder ddl = new DmTableDdlBuilder(new DmDatabaseAdapter());

    @Test
    void buildsQuotedDmCreateTableWithPrimaryKey() {
        String sql = ddl.create(new DmTableDdlBuilder.Definition("SMP", "ORDERS", List.of(
                new DmTableDdlBuilder.Column(null, "ID", "BIGINT", null, null, false, true, true, null, "主键"),
                column(null, "NAME", "VARCHAR", 100, null, true, false, "'new'"),
                column(null, "CREATED_AT", "TIMESTAMP", 36, 0, true, false, null)
        ), null));

        assertThat(sql).startsWith("CREATE TABLE \"SMP\".\"ORDERS\" (");
        assertThat(sql).contains("\"ID\" BIGINT AUTO_INCREMENT NOT NULL", "\"NAME\" VARCHAR(100) DEFAULT 'new'", "\"CREATED_AT\" TIMESTAMP(0)", "PRIMARY KEY (\"ID\")");
        assertThat(ddl.createComments(new DmTableDdlBuilder.Definition("SMP", "ORDERS", List.of(
                new DmTableDdlBuilder.Column(null, "ID", "BIGINT", null, null, false, true, true, null, "主键")
        ), null))).containsExactly("COMMENT ON COLUMN \"SMP\".\"ORDERS\".\"ID\" IS '主键'");
    }

    @Test
    void buildsDmAlterStatementsInSafeOrder() {
        DmTableDdlBuilder.Definition original = new DmTableDdlBuilder.Definition("SMP", "T", List.of(
                column("ID", "ID", "INT", null, null, false, true, null),
                column("TITLE", "TITLE", "VARCHAR", 20, null, true, false, null)
        ), "PK_T");
        DmTableDdlBuilder.Definition target = new DmTableDdlBuilder.Definition("SMP", "T", List.of(
                column("ID", "ID", "BIGINT", null, null, false, true, null),
                column("TITLE", "NAME", "VARCHAR", 80, null, false, false, "'x'"),
                column(null, "CREATED_AT", "TIMESTAMP", 36, 0, true, false, null)
        ), "PK_T");

        assertThat(ddl.alter(original, target)).containsExactly(
                "ALTER TABLE \"SMP\".\"T\" RENAME COLUMN \"TITLE\" TO \"NAME\"",
                "ALTER TABLE \"SMP\".\"T\" MODIFY \"ID\" BIGINT NOT NULL",
                "ALTER TABLE \"SMP\".\"T\" MODIFY \"NAME\" VARCHAR(80) NOT NULL",
                "ALTER TABLE \"SMP\".\"T\" ALTER COLUMN \"NAME\" SET DEFAULT 'x'",
                "ALTER TABLE \"SMP\".\"T\" ADD COLUMN \"CREATED_AT\" TIMESTAMP(0)"
        );
    }

    @Test
    void rejectsUnsafeDefaultsAndUnsupportedTypes() {
        assertThatThrownBy(() -> ddl.create(new DmTableDdlBuilder.Definition("S", "T", List.of(
                column(null, "A", "VARCHAR", 10, null, true, false, "0; DROP TABLE T")
        ), null))).isInstanceOf(RpcException.class);
        assertThatThrownBy(() -> ddl.create(new DmTableDdlBuilder.Definition("S", "T", List.of(
                column(null, "A", "NOT_A_DM_TYPE", null, null, true, false, null)
        ), null))).isInstanceOf(RpcException.class);
    }

    @Test
    void buildsAllDmBuiltInTypeFamilies() {
        String sql = ddl.create(new DmTableDdlBuilder.Definition("S", "ALL_TYPES", List.of(
                column(null, "NAME", "CHAR", 32, null, true, false, null),
                column(null, "AMOUNT", "NUMBER", 18, 2, true, false, null),
                column(null, "PAYLOAD", "VARBINARY", 256, null, true, false, null),
                column(null, "EVENT_TIME", "TIMESTAMP WITH TIME ZONE", null, 6, true, false, null),
                column(null, "DURATION", "INTERVAL DAY TO SECOND", null, null, true, false, null),
                column(null, "CONTENT", "TEXT", null, null, true, false, null)
        ), null));

        assertThat(sql).contains("\"NAME\" CHAR(32)", "\"AMOUNT\" NUMBER(18,2)", "\"PAYLOAD\" VARBINARY(256)",
                "\"EVENT_TIME\" TIMESTAMP(6) WITH TIME ZONE", "\"DURATION\" INTERVAL DAY TO SECOND", "\"CONTENT\" TEXT");
    }

    private static DmTableDdlBuilder.Column column(String original, String name, String type, Integer length,
                                                     Integer scale, boolean nullable, boolean primaryKey, String defaultExpression) {
        return new DmTableDdlBuilder.Column(original, name, type, length, scale, nullable, primaryKey, false, defaultExpression, null);
    }
}
