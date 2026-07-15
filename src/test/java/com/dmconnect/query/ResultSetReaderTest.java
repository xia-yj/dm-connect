package com.dmconnect.query;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

class ResultSetReaderTest {
    @Test
    void boundsLargeTextAndBinaryValuesDuringResultSetRead() throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:h2:mem:result_reader;DB_CLOSE_DELAY=-1");
             var create = connection.createStatement()) {
            create.execute("CREATE TABLE LARGE_VALUES (BODY CLOB, PAYLOAD BLOB)");
            try (var insert = connection.prepareStatement("INSERT INTO LARGE_VALUES VALUES (?, ?)") ) {
                insert.setCharacterStream(1, new StringReader("文".repeat(ResultSetReader.MAX_TEXT_CHARS + 10)));
                insert.setBinaryStream(2, new ByteArrayInputStream(new byte[300]));
                insert.executeUpdate();
            }
            try (var query = connection.createStatement();
                 var rows = query.executeQuery("SELECT BODY, PAYLOAD FROM LARGE_VALUES")) {
                var table = ResultSetReader.read(rows, 1);
                assertThat(table.rows()).hasSize(1);
                assertThat(table.rows().get(0).get(0).toString()).endsWith("…<文本已截断>");
                assertThat(table.rows().get(0).get(1).toString()).endsWith("…<二进制已截断>");
            }
        }
    }
}
