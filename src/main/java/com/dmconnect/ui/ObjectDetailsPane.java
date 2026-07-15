package com.dmconnect.ui;

import com.dmconnect.model.ColumnInfo;
import com.dmconnect.model.ConstraintInfo;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.IndexInfo;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

final class ObjectDetailsPane extends BorderPane {
    ObjectDetailsPane(ObjectLoadResult loaded) {
        getStyleClass().add("object-details");
        TabPane tabs = new TabPane();
        tabs.getStyleClass().add("details-tabs");
        Tab defaultTab = null;
        if (loaded.object().kind() == DatabaseObjectKind.TABLE) {
            tabs.getTabs().add(tab("列", columns(loaded)));
            tabs.getTabs().add(tab("约束", constraints(loaded)));
            tabs.getTabs().add(tab("索引", indexes(loaded)));
            defaultTab = tab("数据预览", preview(loaded));
            tabs.getTabs().add(defaultTab);
        }
        tabs.getTabs().add(tab("DDL", textOrError(loaded.ddl(), loaded.ddlError())));
        if (defaultTab != null) tabs.getSelectionModel().select(defaultTab);
        setTop(buildHeader(loaded));
        setCenter(tabs);
    }

    private Node buildHeader(ObjectLoadResult loaded) {
        Label badge = new Label(switch (loaded.object().kind()) {
            case TABLE -> "T";
            case VIEW -> "V";
            case SEQUENCE -> "#";
            case PROCEDURE -> "P";
            case FUNCTION -> "F";
            case TRIGGER -> "!";
        });
        badge.getStyleClass().add("object-badge");
        Label title = new Label(loaded.object().name());
        title.getStyleClass().add("object-title");
        Label subtitle = new Label(loaded.object().kind().displayName() + "  ·  " + loaded.object().schema());
        subtitle.getStyleClass().add("object-subtitle");
        VBox text = new VBox(2, title, subtitle);
        HBox header = new HBox(11, badge, text);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("object-header");
        return header;
    }

    private Node columns(ObjectLoadResult loaded) {
        if (loaded.details() == null) return error(loaded.detailsError());
        TableView<ColumnInfo> table = new TableView<>();
        table.getColumns().addAll(
                objectColumn("序号", ColumnInfo::ordinal, 55),
                stringColumn("列名", ColumnInfo::name, 150),
                stringColumn("类型", column -> typeText(column), 150),
                stringColumn("可空", column -> column.nullable() ? "是" : "否", 65),
                stringColumn("自增", column -> column.autoIncrement() ? "是" : "否", 65),
                stringColumn("默认值", column -> value(column.defaultValue()), 140),
                stringColumn("备注", column -> value(column.remarks()), 220));
        table.getItems().setAll(loaded.details().columns());
        return table;
    }

    private Node constraints(ObjectLoadResult loaded) {
        if (loaded.details() == null) return error(loaded.detailsError());
        TableView<ConstraintInfo> table = new TableView<>();
        table.getColumns().addAll(
                stringColumn("名称", ConstraintInfo::name, 170),
                stringColumn("类型", ConstraintInfo::type, 110),
                stringColumn("列", item -> String.join(", ", item.columns()), 200),
                stringColumn("引用对象", item -> item.referencedTable() == null ? "" :
                        value(item.referencedSchema()) + "." + item.referencedTable(), 180),
                stringColumn("引用列", item -> String.join(", ", item.referencedColumns()), 160));
        table.getItems().setAll(loaded.details().constraints());
        return table;
    }

    private Node indexes(ObjectLoadResult loaded) {
        if (loaded.details() == null) return error(loaded.detailsError());
        TableView<IndexInfo> table = new TableView<>();
        table.getColumns().addAll(
                stringColumn("名称", IndexInfo::name, 220),
                stringColumn("唯一", item -> item.unique() ? "是" : "否", 80),
                stringColumn("列", item -> String.join(", ", item.columns()), 320));
        table.getItems().setAll(loaded.details().indexes());
        return table;
    }

    private Node preview(ObjectLoadResult loaded) {
        return loaded.preview() == null ? error(loaded.previewError()) : new ResultTablePane(loaded.preview());
    }

    private static Node textOrError(String text, String error) {
        if (text == null) return error(error);
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.setStyle("-fx-font-family: 'Monospaced';");
        return area;
    }

    private static Node error(String message) {
        Label label = new Label(message == null || message.isBlank() ? "没有可显示的信息" : message);
        label.getStyleClass().add("error-label");
        label.setWrapText(true);
        BorderPane pane = new BorderPane(label);
        pane.setPadding(new Insets(20));
        return pane;
    }

    private static Tab tab(String title, Node content) {
        Tab tab = new Tab(title, content);
        tab.setClosable(false);
        return tab;
    }

    private static String typeText(ColumnInfo column) {
        String size = column.size() > 0 ? "(" + column.size() + (column.scale() > 0 ? "," + column.scale() : "") + ")" : "";
        return value(column.typeName()) + size;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

    private static <T> TableColumn<T, String> stringColumn(String title,
                                                            java.util.function.Function<T, String> value,
                                                            double width) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyStringWrapper(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }

    private static <T, V> TableColumn<T, V> objectColumn(String title,
                                                         java.util.function.Function<T, V> value,
                                                         double width) {
        TableColumn<T, V> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(value.apply(cell.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
