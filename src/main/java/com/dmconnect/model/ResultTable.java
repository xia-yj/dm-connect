package com.dmconnect.model;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;

public record ResultTable(List<ColumnMetadata> columns, List<List<Object>> rows, boolean truncated) {
    public ResultTable {
        columns = List.copyOf(columns);
        // JDBC 的 SQL NULL 会以 Java null 出现在行数据中；List.copyOf 会因此抛出 NPE。
        // 使用不可修改的 ArrayList 副本，既保留 NULL，也避免结果被界面意外修改。
        List<List<Object>> rowCopies = new ArrayList<>(rows.size());
        for (List<Object> row : rows) {
            rowCopies.add(Collections.unmodifiableList(new ArrayList<>(row)));
        }
        rows = Collections.unmodifiableList(rowCopies);
    }
}
