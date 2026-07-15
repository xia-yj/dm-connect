package com.dmconnect.connection;

import java.net.URLClassLoader;
import java.sql.Driver;

final class DriverRuntime implements AutoCloseable {
    private final Driver driver;
    private final URLClassLoader classLoader;

    DriverRuntime(Driver driver, URLClassLoader classLoader) {
        this.driver = driver;
        this.classLoader = classLoader;
    }

    Driver driver() {
        return driver;
    }

    @Override
    public void close() throws Exception {
        classLoader.close();
    }
}
