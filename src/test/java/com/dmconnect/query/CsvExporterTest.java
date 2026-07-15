package com.dmconnect.query;

import com.dmconnect.model.ColumnMetadata;
import com.dmconnect.model.ResultTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExporterTest {
    @TempDir
    Path temporary;

    @Test
    void writesUtf8BomAndRfcStyleEscaping() throws Exception {
        ResultTable table = new ResultTable(
                List.of(new ColumnMetadata("NAME", "姓名", "VARCHAR", Types.VARCHAR, true),
                        new ColumnMetadata("NOTE", "备注", "VARCHAR", Types.VARCHAR, true)),
                List.of(List.of("张三", "逗号,引号\"和\n换行"), List.of("李四", "")), false);
        Path csv = temporary.resolve("result.csv");

        CsvExporter.write(table, csv);

        byte[] bytes = Files.readAllBytes(csv);
        assertThat(bytes).startsWith((byte) 0xEF, (byte) 0xBB, (byte) 0xBF);
        String text = new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        assertThat(text).contains("姓名,备注", "张三,\"逗号,引号\"\"和\n换行\"");
    }
}
