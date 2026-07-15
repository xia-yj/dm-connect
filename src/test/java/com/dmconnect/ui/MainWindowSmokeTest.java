package com.dmconnect.ui;

import com.dmconnect.AppContext;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.framework.junit5.ApplicationTest;
import org.testfx.util.WaitForAsyncUtils;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "dmconnect.uiTests", matches = "true")
class MainWindowSmokeTest extends ApplicationTest {
    @TempDir
    Path temporary;
    private AppContext context;
    private MainWindow window;

    @Override
    public void start(Stage stage) throws Exception {
        System.setProperty("dmconnect.dataDir", temporary.toString());
        context = new AppContext();
        window = new MainWindow(stage, context);
        stage.setScene(window.scene());
        stage.show();
    }

    @Test
    void launchesChineseMainWindowAndShowsCoreActions() {
        assertThat(lookup("新建连接").queryAll()).isNotEmpty();
        assertThat(lookup("新建 SQL").queryAll()).isNotEmpty();
        assertThat(lookup("历史").queryAll()).isNotEmpty();
        assertThat(lookup(".app-header").queryAll()).hasSize(1);
        assertThat(lookup(".sidebar").queryAll()).hasSize(1);
        assertThat(lookup(".welcome-card").queryAll()).hasSize(1);
    }

    @AfterEach
    void cleanup() {
        WaitForAsyncUtils.waitForFxEvents();
        if (window != null) window.close();
        if (context != null) context.close();
        System.clearProperty("dmconnect.dataDir");
    }
}
