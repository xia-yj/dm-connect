package com.dmconnect.ui;

import com.dmconnect.model.HistoryEntry;
import com.dmconnect.security.VaultService;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.stage.Window;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Optional;

final class HistoryDialog {
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    private final VaultService vault;
    private final Window owner;
    private final Dialog<HistoryEntry> dialog = new Dialog<>();
    private final TableView<HistoryEntry> table = new TableView<>();
    private final TextArea preview = new TextArea();
    private final Label status = new Label();
    private final ButtonType open = new ButtonType("在 SQL 标签打开");
    private final ButtonType delete = new ButtonType("删除");
    private final ButtonType clear = new ButtonType("清空全部");

    HistoryDialog(Window owner, VaultService vault) {
        this.owner = owner;
        this.vault = vault;
        Dialogs.init(dialog, owner, "SQL 执行历史");
        dialog.getDialogPane().getButtonTypes().addAll(open, delete, clear, ButtonType.CLOSE);
        buildContent();
        configureActions();
        refresh();
    }

    Optional<HistoryEntry> showAndWait() {
        return dialog.showAndWait();
    }

    private void buildContent() {
        TableColumn<HistoryEntry, String> time = column("时间", entry -> TIME_FORMAT.format(entry.executedAt()), 150);
        TableColumn<HistoryEntry, String> connection = column("连接", HistoryEntry::profileName, 130);
        TableColumn<HistoryEntry, String> result = column("结果", entry -> entry.success() ? "成功" : "失败", 65);
        TableColumn<HistoryEntry, String> duration = column("耗时", entry -> entry.durationMillis() + " ms", 80);
        table.getColumns().addAll(time, connection, result, duration);
        table.setPrefHeight(280);
        table.getSelectionModel().selectedItemProperty().addListener((observable, oldValue, value) ->
                preview.setText(value == null ? "" : value.sql()));
        preview.setEditable(false);
        preview.setPrefRowCount(10);
        preview.getStyleClass().add("code-area");
        BorderPane content = new BorderPane(table);
        content.getStyleClass().add("history-content");
        content.setBottom(new javafx.scene.layout.VBox(6, new Label("SQL"), preview, status));
        content.setPadding(new Insets(5));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefSize(760, 620);
    }

    private void configureActions() {
        dialog.getDialogPane().lookupButton(open).addEventFilter(ActionEvent.ACTION, event -> {
            if (table.getSelectionModel().getSelectedItem() == null) {
                status.setText("请先选择一条历史记录");
                event.consume();
            }
        });
        dialog.getDialogPane().lookupButton(delete).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            HistoryEntry selected = table.getSelectionModel().getSelectedItem();
            if (selected == null) return;
            try {
                vault.deleteHistory(selected.id());
                refresh();
            } catch (Exception exception) {
                Dialogs.error(owner, "删除历史失败", exception);
            }
        });
        dialog.getDialogPane().lookupButton(clear).addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            if (!Dialogs.confirm(owner, "清空 SQL 历史", "确认删除全部加密 SQL 历史吗？")) return;
            try {
                vault.clearHistory();
                refresh();
            } catch (Exception exception) {
                Dialogs.error(owner, "清空历史失败", exception);
            }
        });
        dialog.setResultConverter(button -> button == open ? table.getSelectionModel().getSelectedItem() : null);
    }

    private void refresh() {
        try {
            table.getItems().setAll(vault.listHistory());
            status.setText("共 " + table.getItems().size() + " 条，最多保留 1000 条");
            if (!table.getItems().isEmpty()) table.getSelectionModel().selectFirst();
        } catch (Exception exception) {
            status.setText("加载失败：" + Dialogs.rootMessage(exception));
        }
    }

    private static TableColumn<HistoryEntry, String> column(String title,
                                                             java.util.function.Function<HistoryEntry, String> value,
                                                             double width) {
        TableColumn<HistoryEntry, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
