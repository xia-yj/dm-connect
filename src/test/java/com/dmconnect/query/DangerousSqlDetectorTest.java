package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DangerousSqlDetectorTest {
    private final DangerousSqlDetector detector = new DangerousSqlDetector();

    @Test
    void recognizesLeadingDropAndTruncate() {
        assertThat(detector.findDangerous(List.of(
                "-- DBA operation\nDROP TABLE T",
                "/* cleanup */ truncate table T2",
                "select 'drop table safe' from dual")))
                .containsExactly("-- DBA operation\nDROP TABLE T", "/* cleanup */ truncate table T2");
    }

    @Test
    void flagsUnqualifiedDeleteAndUpdate() {
        assertThat(detector.isDangerous("delete from T")).isTrue();
        assertThat(detector.isDangerous("UPDATE users SET active = 0")).isTrue();
        assertThat(detector.isDangerous("DELETE FROM users WHERE id = 1")).isFalse();
        assertThat(detector.isDangerous("UPDATE users SET name = 'where' WHERE id = 1")).isFalse();
        assertThat(detector.isDangerous("UPDATE users SET where_flag = 1")).isTrue();
        assertThat(detector.isDangerous("DELETE FROM users WHERE id IN (SELECT id FROM t WHERE active = 1)")).isFalse();
    }

    @Test
    void flagsSchemaAndPrivilegeChanges() {
        assertThat(detector.isDangerous("ALTER TABLE users ADD COLUMN x INT")).isTrue();
        assertThat(detector.isDangerous("GRANT SELECT ON t TO app")).isTrue();
        assertThat(detector.isDangerous("REVOKE ALL ON t FROM app")).isTrue();
        assertThat(detector.isDangerous("CREATE USER app IDENTIFIED BY secret")).isTrue();
        assertThat(detector.isDangerous("CREATE TABLE users(id INT)")).isFalse();
        assertThat(detector.isDangerous("CREATE OR REPLACE VIEW v AS SELECT 1")).isFalse();
        assertThat(detector.isDangerous("MERGE INTO t USING s ON (t.id = s.id) WHEN MATCHED THEN UPDATE SET t.n = s.n")).isTrue();
    }

    @Test
    void recognizesDangerousMysqlExecutableComments() {
        assertThat(detector.isDangerous("/*!80000 DROP TABLE important */")).isTrue();
        assertThat(detector.isDangerous("/*!40101 SET @OLD_SQL_MODE=@@SQL_MODE */")).isFalse();
    }
}
