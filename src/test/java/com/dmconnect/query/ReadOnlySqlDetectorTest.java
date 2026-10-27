package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReadOnlySqlDetectorTest {
    private final ReadOnlySqlDetector detector = new ReadOnlySqlDetector();

    @Test
    void allowsQueries() {
        assertThat(detector.isReadOnly("select * from t where name = 'update' order by id desc", false)).isTrue();
        assertThat(detector.isReadOnly("-- 注释\nWITH a AS (SELECT 1) SELECT * FROM a", false)).isTrue();
        assertThat(detector.isReadOnly("SHOW TABLES", true)).isTrue();
        assertThat(detector.isReadOnly("explain select delete_flag, \"insert\" from t", false)).isTrue();
        assertThat(detector.isReadOnly("SELECT REPLACE(name, 'a', 'b') FROM t", false)).isTrue();
    }

    @Test
    void rejectsWritesEvenWhenNestedOrHiddenAfterRead() {
        assertThat(detector.isReadOnly("INSERT INTO t VALUES (1)", false)).isFalse();
        assertThat(detector.isReadOnly("WITH d AS (DELETE FROM t RETURNING *) SELECT * FROM d", false)).isFalse();
        assertThat(detector.isReadOnly("SELECT * INTO backup FROM t", false)).isFalse();
        assertThat(detector.isReadOnly("SELECT * FROM t FOR UPDATE", false)).isFalse();
        assertThat(detector.isReadOnly("EXPLAIN ANALYZE DELETE FROM t", false)).isFalse();
        assertThat(detector.isReadOnly("SELECT 1 /*! ; DROP TABLE t */", true)).isFalse();
        assertThat(detector.isReadOnly("", false)).isFalse();
    }

    @Test
    void treatsHashAsCommentOnlyForMySql() {
        String sql = "WITH a AS (SELECT 1 # 1), d AS (DELETE FROM t RETURNING 1) SELECT 1";
        assertThat(detector.findWritable(List.of(sql), "postgresql")).containsExactly(sql);
        assertThat(detector.findWritable(List.of("SELECT 1 # delete\n"), "mysql")).isEmpty();
    }
}
