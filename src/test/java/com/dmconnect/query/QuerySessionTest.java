package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class QuerySessionTest {
    @Test
    void limitsRowsAndMarksTruncation() throws Exception {
        try (QuerySession session = new QuerySession(DriverManager.getConnection("jdbc:h2:mem:limit"))) {
            var result = session.execute(List.of("SELECT X FROM SYSTEM_RANGE(1, 25)"), 10);

            assertThat(result.success()).isTrue();
            assertThat(result.outcomes()).hasSize(1);
            assertThat(result.outcomes().get(0).table().rows()).hasSize(10);
            assertThat(result.outcomes().get(0).table().truncated()).isTrue();
        }
    }

    @Test
    void neverAllowsMoreThanOneHundredRows() throws Exception {
        try (QuerySession session = new QuerySession(DriverManager.getConnection("jdbc:h2:mem:absolute-limit"))) {
            var result = session.execute(List.of("SELECT X FROM SYSTEM_RANGE(1, 125)"), 10_000);

            assertThat(result.success()).isTrue();
            assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.table().rows()).hasSize(QuerySession.MAX_RESULT_ROWS);
                assertThat(outcome.table().truncated()).isTrue();
            });
        }
    }

    @Test
    void supportsManualCommitAndRollback() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:transaction;DB_CLOSE_DELAY=-1")) {
            connection.createStatement().execute("CREATE TABLE T(ID INT)");
        }
        try (QuerySession session = new QuerySession(DriverManager.getConnection("jdbc:h2:mem:transaction"))) {
            session.setAutoCommit(false);
            assertThat(session.execute(List.of("INSERT INTO T VALUES(1)"), 100).success()).isTrue();
            assertThat(session.hasPendingTransaction()).isTrue();
            session.rollback();
            assertThat(session.hasPendingTransaction()).isFalse();
            var count = session.execute(List.of("SELECT COUNT(*) AS C FROM T"), 100);
            assertThat(count.outcomes().get(0).table().rows().get(0).get(0).toString()).isEqualTo("0");
        }
    }

    @Test
    void reportsSqlStateAndKeepsEarlierOutcomes() throws Exception {
        try (QuerySession session = new QuerySession(DriverManager.getConnection("jdbc:h2:mem:error"))) {
            var result = session.execute(List.of("SELECT 1 AS OK", "SELECT * FROM MISSING_TABLE"), 100);

            assertThat(result.success()).isFalse();
            assertThat(result.outcomes()).hasSize(1);
            assertThat(result.sqlState()).isNotBlank();
            assertThat(result.executedStatements()).isEqualTo(2);
        }
    }

    @Test
    void preservesDatabaseNullValuesWithoutThrowingNullPointerException() throws Exception {
        try (QuerySession session = new QuerySession(DriverManager.getConnection("jdbc:h2:mem:null-values"))) {
            var result = session.execute(List.of("SELECT 1 AS ID, CAST(NULL AS VARCHAR) AS OPTIONAL_VALUE"), 100);

            assertThat(result.success()).isTrue();
            assertThat(result.outcomes()).singleElement().satisfies(outcome -> {
                assertThat(outcome.table().rows()).hasSize(1);
                assertThat(outcome.table().rows().get(0)).containsExactly(1, null);
            });
        }
    }
}
