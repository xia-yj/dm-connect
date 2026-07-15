package com.dmconnect.query;

import com.dmconnect.database.dm.DmDatabaseAdapter;
import com.dmconnect.database.mysql.MySqlDatabaseAdapter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcStreamWriterTest {
    @Test
    void streamsHexMysqlAndStandardSqlLiteralsWithoutMaterializingTheValue() throws Exception {
        StringWriter hex = new StringWriter();
        JdbcStreamWriter.writeHex(hex, new ByteArrayInputStream(new byte[]{0, 1, (byte) 0xff}));
        assertThat(hex.toString()).isEqualTo("0001ff");

        StringWriter mysql = new StringWriter();
        JdbcStreamWriter.writeSqlTextLiteral(mysql, new StringReader("a'b\\\n中"),
                new MySqlDatabaseAdapter());
        assertThat(mysql.toString()).isEqualTo("CONVERT(X'6127625c0ae4b8ad' USING utf8mb4)");

        StringWriter dm = new StringWriter();
        JdbcStreamWriter.writeSqlTextLiteral(dm, new StringReader("a'b"), new DmDatabaseAdapter());
        assertThat(dm.toString()).isEqualTo("'a''b'");
    }

    @Test
    void streamsCsvTextWithQuotesAndNewlinesEscaped() throws Exception {
        StringWriter csv = new StringWriter();
        JdbcStreamWriter.writeCsvText(csv, new StringReader("a\"b\n第二行"));
        assertThat(csv.toString()).isEqualTo("\"a\"\"b\n第二行\"");
    }
}
