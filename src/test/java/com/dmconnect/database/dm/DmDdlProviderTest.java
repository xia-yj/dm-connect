package com.dmconnect.database.dm;

import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DmDdlProviderTest {
    private static final DatabaseObject TABLE = new DatabaseObject(
            "BIG_DATA", "SEC_DAILY_REPORT", DatabaseObjectKind.TABLE, "");

    @Test
    void preservesChineseCommentsAndEscapesSqlText() {
        assertThat(DmDdlProvider.qualified("业务库", "工单表"))
                .isEqualTo("\"业务库\".\"工单表\"");
        assertThat(DmDdlProvider.literal("处理人\nO'Reilly"))
                .isEqualTo("'处理人 O''Reilly'");
    }

    @Test
    void appendsExecutableLongRowStatementWhenMetadataOnlyHasTheTableOption() {
        String metadataDdl = "CREATE TABLE \"BIG_DATA\".\"SEC_DAILY_REPORT\" (ID BIGINT) USING LONG ROW";

        String ddl = DmDdlProvider.appendLongRowSql(TABLE, metadataDdl,
                DmDdlProvider.hasLongRow(metadataDdl));

        assertThat(ddl).isEqualTo("""
                CREATE TABLE "BIG_DATA"."SEC_DAILY_REPORT" (ID BIGINT) USING LONG ROW;

                ALTER TABLE "BIG_DATA"."SEC_DAILY_REPORT" ENABLE USING LONG ROW;""");
    }

    @Test
    void doesNotDuplicateLongRowStatementAlreadyReturnedByMetadata() {
        String metadataDdl = """
                CREATE TABLE "BIG_DATA"."SEC_DAILY_REPORT" (ID BIGINT);

                ALTER TABLE "BIG_DATA"."SEC_DAILY_REPORT" ENABLE USING LONG ROW;""";

        assertThat(DmDdlProvider.appendLongRowSql(TABLE, metadataDdl,
                DmDdlProvider.hasLongRow(metadataDdl))).isEqualTo(metadataDdl);
    }

    @Test
    void leavesDdlAloneWhenLongRowIsNotEnabled() {
        String ddl = "CREATE TABLE \"BIG_DATA\".\"SEC_DAILY_REPORT\" (ID BIGINT);";

        assertThat(DmDdlProvider.appendLongRowSql(TABLE, ddl, false)).isEqualTo(ddl);
    }
}
