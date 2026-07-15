package com.dmconnect.connection;

import com.dmconnect.database.mysql.MySqlDatabaseAdapter;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverPropertyInfo;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;

class ConnectionServiceTest {
    @TempDir
    Path temporary;

    @Test
    void closesConnectionThatFinishesAfterWaitingThreadIsInterrupted() throws Exception {
        AppPaths paths = new AppPaths(temporary.resolve("data"), temporary.resolve("data/drivers"),
                temporary.resolve("data/profiles.json"), temporary.resolve("data/drivers.json"),
                temporary.resolve("data/vault.json"));
        paths.initialize();

        LateReturningDriver driver = new LateReturningDriver();
        try (DriverManagerService drivers = new DriverManagerService(paths, new JsonStore());
             ConnectionService connections = new ConnectionService(new MySqlDatabaseAdapter(), drivers)) {
            replaceBuiltInDriver(drivers, driver);
            ConnectionProfile profile = ConnectionProfile.create(
                    "mysql", "test", "localhost", 3306, "sample", "user",
                    DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, Map.of(), false);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicBoolean interruptRestored = new AtomicBoolean();
            Thread waitingThread = new Thread(() -> {
                try {
                    connections.connect(profile, new char[0], Duration.ofSeconds(5));
                } catch (Throwable exception) {
                    failure.set(exception);
                    interruptRestored.set(Thread.currentThread().isInterrupted());
                }
            }, "connection-waiter");

            waitingThread.start();
            assertThat(driver.connectStarted.await(1, TimeUnit.SECONDS)).isTrue();
            waitingThread.interrupt();
            waitingThread.join(1_000);

            assertThat(waitingThread.isAlive()).isFalse();
            assertThat(failure.get())
                    .isInstanceOf(SQLException.class)
                    .hasMessage("连接过程被中断");
            assertThat(interruptRestored).isTrue();
            assertThat(driver.workerInterrupted.await(1, TimeUnit.SECONDS)).isTrue();

            driver.allowConnectionReturn.countDown();
            assertThat(driver.connectionClosed.await(1, TimeUnit.SECONDS)).isTrue();
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceBuiltInDriver(DriverManagerService drivers, Driver driver) throws Exception {
        Field loadedField = DriverManagerService.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        ConcurrentHashMap<String, DriverRuntime> loaded =
                (ConcurrentHashMap<String, DriverRuntime>) loadedField.get(drivers);
        loaded.put(DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, new DriverRuntime(driver));
    }

    private static final class LateReturningDriver implements Driver {
        private final CountDownLatch connectStarted = new CountDownLatch(1);
        private final CountDownLatch workerInterrupted = new CountDownLatch(1);
        private final CountDownLatch allowConnectionReturn = new CountDownLatch(1);
        private final CountDownLatch connectionClosed = new CountDownLatch(1);

        @Override
        public Connection connect(String url, Properties info) {
            connectStarted.countDown();
            boolean released = false;
            while (!released) {
                try {
                    released = allowConnectionReturn.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    workerInterrupted.countDown();
                }
            }
            return (Connection) Proxy.newProxyInstance(
                    ConnectionServiceTest.class.getClassLoader(),
                    new Class<?>[]{Connection.class},
                    (proxy, method, arguments) -> {
                        if (method.getName().equals("close")) {
                            connectionClosed.countDown();
                            return null;
                        }
                        if (method.getReturnType().equals(boolean.class)) return false;
                        if (method.getReturnType().equals(int.class)) return 0;
                        if (method.getReturnType().equals(long.class)) return 0L;
                        return null;
                    });
        }

        @Override
        public boolean acceptsURL(String url) {
            return true;
        }

        @Override
        public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
            return new DriverPropertyInfo[0];
        }

        @Override
        public int getMajorVersion() {
            return 1;
        }

        @Override
        public int getMinorVersion() {
            return 0;
        }

        @Override
        public boolean jdbcCompliant() {
            return false;
        }

        @Override
        public Logger getParentLogger() {
            return Logger.getGlobal();
        }
    }
}
