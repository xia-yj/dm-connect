package com.dmconnect.connection;

import com.dmconnect.database.generic.GenericJdbcAdapter;
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
import java.util.LinkedHashMap;
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
            replaceLoadedDriver(drivers, DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, driver);
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

    @Test
    void appliesMysqlConnectDefaultsAndLetsTheProfileOverrideThem() throws Exception {
        AppPaths paths = new AppPaths(temporary.resolve("data"), temporary.resolve("data/drivers"),
                temporary.resolve("data/profiles.json"), temporary.resolve("data/drivers.json"),
                temporary.resolve("data/vault.json"));
        paths.initialize();
        RecordingDriver driver = new RecordingDriver();
        try (DriverManagerService drivers = new DriverManagerService(paths, new JsonStore());
             ConnectionService connections = new ConnectionService(new MySqlDatabaseAdapter(), drivers)) {
            replaceLoadedDriver(drivers, DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, driver);
            ConnectionProfile profile = ConnectionProfile.create(
                    "mysql", "test", "localhost", 3306, "sample", "user",
                    DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, Map.of(), false);
            try (Connection ignored = connections.connect(profile, new char[0])) {
                assertThat(driver.lastUrl).contains("connectTimeout=30000").contains("tcpKeepAlive=true");
            }

            ConnectionProfile override = ConnectionProfile.create(
                    "mysql", "test", "localhost", 3306, "sample", "user",
                    DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID, Map.of("connectTimeout", "5000"), false);
            try (Connection ignored = connections.connect(override, new char[0])) {
                assertThat(driver.lastUrl).contains("connectTimeout=5000").contains("tcpKeepAlive=true")
                        .doesNotContain("connectTimeout=30000");
            }
        }
    }

    @Test
    void appliesSqlServerTrustDefaultsThroughDriverProperties() throws Exception {
        AppPaths paths = new AppPaths(temporary.resolve("data"), temporary.resolve("data/drivers"),
                temporary.resolve("data/profiles.json"), temporary.resolve("data/drivers.json"),
                temporary.resolve("data/vault.json"));
        paths.initialize();
        RecordingDriver driver = new RecordingDriver();
        GenericJdbcAdapter adapter = new GenericJdbcAdapter("sqlserver", "SQL Server",
                "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, GenericJdbcAdapter.Flavor.SQLSERVER);
        try (DriverManagerService drivers = new DriverManagerService(paths, new JsonStore());
             ConnectionService connections = new ConnectionService(Map.of("sqlserver", adapter), drivers)) {
            replaceLoadedDriver(drivers, DriverManagerService.BUILT_IN_SQLSERVER_DRIVER_ID, driver);
            ConnectionProfile profile = ConnectionProfile.create(
                    "sqlserver", "test", "localhost", 1433, "app", "sa",
                    DriverManagerService.BUILT_IN_SQLSERVER_DRIVER_ID, Map.of(), false);
            try (Connection ignored = connections.connect(profile, new char[0])) {
                assertThat(driver.lastUrl).isEqualTo("jdbc:sqlserver://localhost:1433;databaseName=app");
                assertThat(driver.lastProperties)
                        .containsEntry("encrypt", "true")
                        .containsEntry("trustServerCertificate", "true")
                        .containsEntry("loginTimeout", "30");
            }

            ConnectionProfile strict = ConnectionProfile.create(
                    "sqlserver", "test", "localhost", 1433, "app", "sa",
                    DriverManagerService.BUILT_IN_SQLSERVER_DRIVER_ID,
                    Map.of("trustServerCertificate", "false"), false);
            try (Connection ignored = connections.connect(strict, new char[0])) {
                assertThat(driver.lastProperties).containsEntry("trustServerCertificate", "false")
                        .containsEntry("encrypt", "true");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static void replaceLoadedDriver(DriverManagerService drivers, String id, Driver driver) throws Exception {
        Field loadedField = DriverManagerService.class.getDeclaredField("loaded");
        loadedField.setAccessible(true);
        ConcurrentHashMap<String, DriverRuntime> loaded =
                (ConcurrentHashMap<String, DriverRuntime>) loadedField.get(drivers);
        loaded.put(id, new DriverRuntime(driver));
    }

    private static Connection dummyConnection(Runnable onClose) {
        return (Connection) Proxy.newProxyInstance(
                ConnectionServiceTest.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("close")) {
                        if (onClose != null) onClose.run();
                        return null;
                    }
                    if (method.getName().equals("isValid")) return true;
                    if (method.getName().equals("isClosed")) return false;
                    if (method.getReturnType().equals(boolean.class)) return false;
                    if (method.getReturnType().equals(int.class)) return 0;
                    if (method.getReturnType().equals(long.class)) return 0L;
                    return null;
                });
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
            return dummyConnection(connectionClosed::countDown);
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

    private static final class RecordingDriver implements Driver {
        private String lastUrl;
        private Map<String, String> lastProperties = Map.of();

        @Override
        public Connection connect(String url, Properties info) {
            lastUrl = url;
            Map<String, String> copy = new LinkedHashMap<>();
            if (info != null) {
                info.stringPropertyNames().forEach(key -> copy.put(key, info.getProperty(key)));
            }
            lastProperties = copy;
            return dummyConnection(null);
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
