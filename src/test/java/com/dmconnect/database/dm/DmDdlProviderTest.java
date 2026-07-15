package com.dmconnect.database.dm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DmDdlProviderTest {
    @Test
    void preservesChineseCommentsAndEscapesSqlText() {
        assertThat(DmDdlProvider.qualified("业务库", "工单表"))
                .isEqualTo("\"业务库\".\"工单表\"");
        assertThat(DmDdlProvider.literal("处理人\nO'Reilly"))
                .isEqualTo("'处理人 O''Reilly'");
    }
}
