package com.dmconnect;

import javafx.application.Application;

/**
 * 非 JavaFX Application 子类的打包入口，避免类路径模式下 JDK 启动器误判 JavaFX 模块缺失。
 */
public final class DmConnectLauncher {
    private DmConnectLauncher() {
    }

    public static void main(String[] args) {
        Application.launch(DmConnectApplication.class, args);
    }
}
