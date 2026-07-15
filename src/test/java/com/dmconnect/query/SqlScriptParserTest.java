package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SqlScriptParserTest {
    private final SqlScriptParser parser = new SqlScriptParser();

    @Test
    void splitsNormalStatementsWithoutBreakingStringsOrComments() {
        String sql = """
                -- 第一条
                select 'a;''b' as value;
                /* ; */ update TEST set NAME = 'x;y' where ID = 1;
                """;

        assertThat(parser.split(sql)).hasSize(2);
        assertThat(parser.split(sql).get(0)).contains("select 'a;''b'");
        assertThat(parser.split(sql).get(1)).contains("update TEST");
    }

    @Test
    void keepsProceduralBlockUntilStandaloneSlash() {
        String sql = """
                CREATE OR REPLACE PROCEDURE P_TEST AS
                BEGIN
                  INSERT INTO T VALUES (1);
                  COMMIT;
                END;
                /
                SELECT * FROM T;
                """;

        assertThat(parser.split(sql)).hasSize(2);
        assertThat(parser.split(sql).get(0)).contains("INSERT INTO", "END;").doesNotContain("\n/");
        assertThat(parser.split(sql).get(1)).isEqualTo("SELECT * FROM T");
    }

    @Test
    void findsStatementAtCaret() {
        String sql = "select 1;\nselect 2;";
        assertThat(parser.currentStatement(sql, sql.indexOf('2')))
                .get().extracting(ParsedStatement::sql).isEqualTo("select 2");
    }

}
