package com.dmconnect.ui;

import com.dmconnect.AppContext;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.model.DriverDescriptor;
import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

final class ConnectionProfileDialog {
    record Result(ConnectionProfile profile, char[] password, boolean passwordProvided) {
    }

    private final AppContext context;
    private final ConnectionProfile original;
    private final Dialog<Result> dialog = new Dialog<>();
    private final TextField name = new TextField();
    private final TextField host = new TextField("localhost");
    private final Spinner<Integer> port = new Spinner<>(1, 65_535, 5236);
    private final TextField username = new TextField();
    private final PasswordField password = new PasswordField();
    private final ComboBox<DriverDescriptor> driver = new ComboBox<>();
    private final CheckBox remember = new CheckBox("加密保存密码");
    private final TextArea advanced = new TextArea();
    private final Label status = new Label();
    private final ButtonType save = new ButtonType("保存");
    private final ButtonType test = new ButtonType("测试连接");

    ConnectionProfileDialog(Window owner, AppContext context, ConnectionProfile original) throws Exception {
        this.context = context;
        this.original = original;
        Dialogs.init(dialog, owner, original == null ? "新建达梦连接" : "编辑达梦连接");
        dialog.getDialogPane().getButtonTypes().addAll(test, save, ButtonType.CANCEL);
        buildForm(context.drivers().findAll());
        if (original != null) populate(original);
        configureButtons();
    }

    Optional<Result> showAndWait() {
        return dialog.showAndWait();
    }

    private void buildForm(List<DriverDescriptor> drivers) {
        driver.getItems().setAll(drivers);
        if (!drivers.isEmpty()) driver.getSelectionModel().selectFirst();
        port.setEditable(true);
        password.setPromptText(original == null ? "数据库密码" : "留空则保持原密码");
        advanced.setPromptText("每行一个 key=value，例如：\nsocketTimeout=30000");
        advanced.setPrefRowCount(4);
        remember.setSelected(true);
        status.setWrapText(true);

        GridPane grid = new GridPane();
        grid.getStyleClass().add("form-grid");
        grid.setHgap(10);
        grid.setVgap(10);
        grid.addRow(0, new Label("连接名称 *"), name);
        grid.addRow(1, new Label("主机 *"), host);
        grid.addRow(2, new Label("端口 *"), port);
        grid.addRow(3, new Label("用户名 *"), username);
        grid.addRow(4, new Label("密码"), password);
        grid.addRow(5, new Label("JDBC 驱动 *"), driver);
        grid.add(remember, 1, 6);
        grid.addRow(7, new Label("高级参数"), advanced);
        grid.add(status, 0, 8, 2, 1);
        name.setPrefColumnCount(28);
        Label heading = new Label(original == null ? "连接到达梦数据库" : "编辑连接配置");
        heading.getStyleClass().add("dialog-heading");
        Label description = new Label("驱动在本机独立加载，密码可选择在本地加密保存。");
        description.getStyleClass().add("dialog-description");
        VBox content = new VBox(5, heading, description, grid);
        content.setPadding(new Insets(8));
        VBox.setMargin(grid, new Insets(12, 0, 0, 0));
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(560);
    }

    private void populate(ConnectionProfile profile) {
        name.setText(profile.name());
        host.setText(profile.host());
        port.getValueFactory().setValue(profile.port());
        username.setText(profile.username());
        remember.setSelected(profile.rememberPassword());
        driver.getItems().stream().filter(item -> item.id().equals(profile.driverId())).findFirst()
                .ifPresent(item -> driver.getSelectionModel().select(item));
        StringBuilder properties = new StringBuilder();
        profile.advancedProperties().forEach((key, value) -> properties.append(key).append('=').append(value).append('\n'));
        advanced.setText(properties.toString());
    }

    private void configureButtons() {
        Node saveButton = dialog.getDialogPane().lookupButton(save);
        saveButton.addEventFilter(ActionEvent.ACTION, event -> {
            try {
                validate();
            } catch (IllegalArgumentException exception) {
                status.setText(exception.getMessage());
                status.getStyleClass().add("error-label");
                event.consume();
            }
        });

        Node testButton = dialog.getDialogPane().lookupButton(test);
        testButton.addEventFilter(ActionEvent.ACTION, event -> {
            event.consume();
            testConnection(testButton, saveButton);
        });
        dialog.setResultConverter(button -> {
            if (button != save) return null;
            char[] value = password.getText().toCharArray();
            return new Result(buildProfile(), value, value.length > 0);
        });
    }

    private void testConnection(Node testButton, Node saveButton) {
        try {
            validate();
        } catch (IllegalArgumentException exception) {
            status.setText(exception.getMessage());
            return;
        }
        char[] entered = password.getText().toCharArray();
        char[] effective = entered;
        try {
            if (effective.length == 0 && original != null) {
                effective = context.vault().getSecret(original.id()).orElse(new char[0]);
            }
            if (effective.length == 0) {
                status.setText("请输入数据库密码后再测试");
                return;
            }
        } catch (Exception exception) {
            status.setText(Dialogs.rootMessage(exception));
            return;
        }
        char[] passwordCopy = effective.clone();
        Arrays.fill(entered, '\0');
        if (effective != entered) Arrays.fill(effective, '\0');
        testButton.setDisable(true);
        saveButton.setDisable(true);
        status.setText("正在连接…");
        ConnectionProfile profile = buildProfile();
        CompletableFuture.runAsync(() -> {
            try {
                context.connections().testConnection(profile, passwordCopy);
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                Arrays.fill(passwordCopy, '\0');
            }
        }, context.executor()).whenComplete((ignored, throwable) -> Platform.runLater(() -> {
            testButton.setDisable(false);
            saveButton.setDisable(false);
            if (throwable == null) {
                status.setText("连接成功");
                status.getStyleClass().remove("error-label");
            } else {
                status.setText("连接失败：" + Dialogs.rootMessage(throwable));
                if (!status.getStyleClass().contains("error-label")) status.getStyleClass().add("error-label");
            }
        }));
    }

    private void validate() {
        if (name.getText().isBlank()) throw new IllegalArgumentException("连接名称不能为空");
        if (host.getText().isBlank()) throw new IllegalArgumentException("主机不能为空");
        if (username.getText().isBlank()) throw new IllegalArgumentException("用户名不能为空");
        if (driver.getValue() == null) throw new IllegalArgumentException("请选择 JDBC 驱动");
        parseAdvanced();
    }

    private ConnectionProfile buildProfile() {
        Map<String, String> properties = parseAdvanced();
        if (original == null) {
            return ConnectionProfile.create(name.getText().strip(), host.getText().strip(), port.getValue(),
                    username.getText().strip(), driver.getValue().id(), properties, remember.isSelected());
        }
        return new ConnectionProfile(original.id(), name.getText().strip(), original.databaseType(),
                host.getText().strip(), port.getValue(), original.database(), username.getText().strip(), driver.getValue().id(),
                properties, remember.isSelected());
    }

    private Map<String, String> parseAdvanced() {
        Map<String, String> properties = new LinkedHashMap<>();
        String[] lines = advanced.getText().split("\\R");
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) continue;
            int separator = line.indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("高级参数第 " + (i + 1) + " 行应为 key=value");
            String key = line.substring(0, separator).strip();
            String value = line.substring(separator + 1).strip();
            if (key.equalsIgnoreCase("user") || key.equalsIgnoreCase("password")) {
                throw new IllegalArgumentException("用户名和密码不能写入高级参数");
            }
            properties.put(key, value);
        }
        return properties;
    }
}
