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

    @Test
    void parsesMySqlBackticksCommentsEscapesAndDelimiterBlocks() {
        String sql = """
                # MySQL comment
                SELECT `semi;column`, 'it\\'s;ok' FROM `order`;
                DELIMITER $$
                CREATE PROCEDURE p_test()
                BEGIN
                  SELECT 'inside;procedure';
                END$$
                DELIMITER ;
                SELECT 2;
                """;

        assertThat(parser.split(sql, "mysql")).hasSize(3);
        assertThat(parser.split(sql, "mysql").get(0)).contains("`semi;column`");
        assertThat(parser.split(sql, "mysql").get(1)).startsWith("CREATE PROCEDURE").contains("inside;procedure");
        assertThat(parser.split(sql, "mysql").get(2)).isEqualTo("SELECT 2");
    }

    @Test
    void preservesMysqlExecutableCommentsAndRequiresWhitespaceAfterDashCommentMarker() {
        String sql = """
                /*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */;
                SELECT 4--2;
                -- actual comment
                SELECT 3;
                """;

        assertThat(parser.split(sql, "mysql")).containsExactly(
                "/*!40101 SET @OLD_CHARACTER_SET_CLIENT=@@CHARACTER_SET_CLIENT */",
                "SELECT 4--2",
                "-- actual comment\nSELECT 3");
    }

    @Test
    void followsMysqlNoBackslashEscapesQuoteSemantics() {
        String sql = "SELECT 'trailing\\'; SELECT 2;";

        assertThat(parser.split(sql, "mysql", false)).containsExactly(
                "SELECT 'trailing\\'", "SELECT 2");
        assertThat(parser.split(sql, "mysql", true)).containsExactly(sql);
    }

    @Test
    void followsMysqlAnsiQuotesSemanticsWithoutChangingSingleQuotedStrings() {
        String doubleQuotedIdentifier = "SELECT \"trailing\\\"; SELECT 2;";
        String singleQuotedString = "SELECT 'trailing\\'; SELECT 2;";

        assertThat(parser.split(doubleQuotedIdentifier, "mysql", true, true))
                .containsExactly("SELECT \"trailing\\\"", "SELECT 2");
        assertThat(parser.split(doubleQuotedIdentifier, "mysql", true, false))
                .containsExactly(doubleQuotedIdentifier);
        assertThat(parser.split(singleQuotedString, "mysql", true, true))
                .containsExactly(singleQuotedString);
    }

}
