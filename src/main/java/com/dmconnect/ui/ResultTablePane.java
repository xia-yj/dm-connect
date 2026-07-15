package com.dmconnect.ui;

import com.dmconnect.model.ResultTable;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import java.util.List;

final class ResultTablePane extends BorderPane {
    private final ResultTable resultTable;

    ResultTablePane(ResultTable resultTable) {
        this.resultTable = resultTable;
        getStyleClass().add("result-table-pane");
        TableView<List<Object>> table = new TableView<>();
        table.getStyleClass().add("data-table");
        table.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        for (int index = 0; index < resultTable.columns().size(); index++) {
            int columnIndex = index;
            var metadata = resultTable.columns().get(index);
            TableColumn<List<Object>, Object> column = new TableColumn<>(metadata.label());
            column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().get(columnIndex)));
            column.setPrefWidth(Math.max(110, Math.min(260, metadata.label().length() * 18 + 40)));
            table.getColumns().add(column);
        }
        table.getItems().setAll(resultTable.rows());
        setCenter(table);
        Label status = new Label("共 " + resultTable.rows().size() + " 行"
                + (resultTable.truncated() ? "（已达到上限，结果已截断）" : ""));
        status.getStyleClass().add(resultTable.truncated() ? "error-label" : "muted-label");
        HBox footer = new HBox(status);
        footer.getStyleClass().add("result-footer");
        setBottom(footer);
    }

    ResultTable resultTable() {
        return resultTable;
    }
}
