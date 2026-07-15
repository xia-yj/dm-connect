package com.dmconnect;

import com.dmconnect.ui.MainWindow;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;

public final class DmConnectApplication extends Application {
    private AppContext context;
    private MainWindow mainWindow;

    @Override
    public void start(Stage stage) {
        try {
            context = new AppContext();
            mainWindow = new MainWindow(stage, context);
            stage.setTitle("DM Connect");
            stage.setMinWidth(1100);
            stage.setMinHeight(700);
            stage.setScene(mainWindow.scene());
            stage.setOnCloseRequest(event -> {
                if (mainWindow != null) mainWindow.close();
            });
            stage.show();
        } catch (Exception exception) {
            exception.printStackTrace();
            Platform.exit();
        }
    }

    @Override
    public void stop() {
        if (mainWindow != null) mainWindow.close();
        if (context != null) context.close();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
