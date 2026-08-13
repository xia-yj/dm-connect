package com.dmconnect.backend;

import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import org.junit.jupiter.api.Test;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TableSessionTest {
    private static final DatabaseObject TABLE = new DatabaseObject("PUBLIC", "T", DatabaseObjectKind.TABLE, "");

    @Test
    void reconnectsAStaleSessionAndKeepsItsCommitMode() throws Exception {
        String url = "jdbc:h2:mem:table_reconnect;DB_CLOSE_DELAY=-1";
        var stale = DriverManager.getConnection(url);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();
        try (TableSession session = new TableSession("profile", TABLE, () -> {
            if (opens.getAndIncrement() == 0) return stale;
            reconnects.incrementAndGet();
            return DriverManager.getConnection(url);
        })) {
            session.setAutoCommit(false);
            stale.close();

            assertThat(session.connection().isClosed()).isFalse();
            assertThat(session.isAutoCommit()).isFalse();
            assertThat(reconnects).hasValue(1);
        }
    }

    @Test
    void doesNotReconnectWhenAnUncommittedTransactionWasLost() throws Exception {
        String url = "jdbc:h2:mem:table_lost_transaction;DB_CLOSE_DELAY=-1";
        var stale = DriverManager.getConnection(url);
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger reconnects = new AtomicInteger();
        try (TableSession session = new TableSession("profile", TABLE, () -> {
            if (opens.getAndIncrement() == 0) return stale;
            reconnects.incrementAndGet();
            return DriverManager.getConnection(url);
        })) {
            session.setAutoCommit(false);
            session.markPendingIfNeeded();
            stale.close();

            assertThatThrownBy(session::connection)
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("未提交事务无法自动恢复");
            assertThat(reconnects).hasValue(0);
        }
    }
}
