package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DangerousSqlDetectorTest {
    private final DangerousSqlDetector detector = new DangerousSqlDetector();

    @Test
    void recognizesOnlyLeadingDropAndTruncate() {
        assertThat(detector.findDangerous(List.of(
                "-- DBA operation\nDROP TABLE T",
                "/* cleanup */ truncate table T2",
                "select 'drop table safe' from dual",
                "delete from T")))
                .containsExactly("-- DBA operation\nDROP TABLE T", "/* cleanup */ truncate table T2");
    }
}
