package com.dmconnect.ui;

import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.Optional;

final class Dialogs {
    private Dialogs() {
    }

    static void info(Window owner, String title, String message) {
        showAlert(owner, Alert.AlertType.INFORMATION, title, message);
    }

    static void error(Window owner, String title, Throwable throwable) {
        String message = rootMessage(throwable);
        showAlert(owner, Alert.AlertType.ERROR, title, message);
    }

    static void error(Window owner, String title, String message) {
        showAlert(owner, Alert.AlertType.ERROR, title, message);
    }

    static boolean confirm(Window owner, String title, String message) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION, message, ButtonType.CANCEL, ButtonType.OK);
        init(alert, owner, title);
        return alert.showAndWait().filter(ButtonType.OK::equals).isPresent();
    }

    static Optional<char[]> password(Window owner, String title, String message) {
        Dialog<char[]> dialog = new Dialog<>();
        init(dialog, owner, title);
        PasswordField field = new PasswordField();
        field.setPromptText("数据库密码");
        VBox box = new VBox(8, new Label(message), field);
        dialog.getDialogPane().setContent(box);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node ok = dialog.getDialogPane().lookupButton(ButtonType.OK);
        ok.disableProperty().bind(field.textProperty().isEmpty());
        dialog.setResultConverter(button -> button == ButtonType.OK ? field.getText().toCharArray() : null);
        return dialog.showAndWait();
    }

    static String rootMessage(Throwable throwable) {
        if (throwable == null) return "未知错误";
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    static void init(Dialog<?> dialog, Window owner, String title) {
        dialog.setTitle(title);
        // JavaFX HeavyweightDialog 在 owner 尚未绑定 Scene（首次启动阶段）时会触发 NPE。
        if (owner != null && owner.getScene() != null) dialog.initOwner(owner);
        DialogPane pane = dialog.getDialogPane();
        pane.setMinWidth(420);
        var css = Dialogs.class.getResource("/com/dmconnect/ui/application.css");
        if (css != null && !pane.getStylesheets().contains(css.toExternalForm())) {
            pane.getStylesheets().add(css.toExternalForm());
        }
    }

    private static void showAlert(Window owner, Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type, message, ButtonType.OK);
        init(alert, owner, title);
        alert.showAndWait();
    }
}
