package com.dmconnect.ui;

import com.dmconnect.model.ColumnMetadata;
import com.dmconnect.model.DatabaseObject;
import com.dmconnect.model.DatabaseObjectKind;
import com.dmconnect.model.ResultTable;
import com.dmconnect.model.TableDetails;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.testfx.framework.junit5.ApplicationTest;

import java.sql.Types;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "dmconnect.uiTests", matches = "true")
class ObjectDetailsPaneTest extends ApplicationTest {
    private ObjectDetailsPane pane;

    @Override
    public void start(Stage stage) {
        DatabaseObject table = new DatabaseObject("TEST", "T_USER", DatabaseObjectKind.TABLE, "");
        TableDetails details = new TableDetails(table, List.of(), List.of(), List.of());
        ResultTable preview = new ResultTable(
                List.of(new ColumnMetadata("ID", "ID", "INTEGER", Types.INTEGER, false)),
                List.of(List.of(1)), false);
        pane = new ObjectDetailsPane(new ObjectLoadResult(table, details, "", preview, "", "CREATE TABLE T_USER", ""));
        stage.setScene(new Scene(pane, 600, 400));
        stage.show();
    }

    @Test
    void selectsDataPreviewByDefaultForTable() {
        TabPane tabs = (TabPane) pane.getCenter();
        assertThat(tabs.getSelectionModel().getSelectedItem().getText()).isEqualTo("数据预览");
        assertThat(pane.getStyleClass()).contains("object-details");
        assertThat(tabs.getStyleClass()).contains("details-tabs");
    }
}
