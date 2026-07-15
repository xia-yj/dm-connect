package com.dmconnect.connection;

import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.ConnectionProfile;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ConnectionService implements AutoCloseable {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(10);
    private final DatabaseAdapter adapter;
    private final DriverManagerService drivers;
    private final ExecutorService connectorExecutor;

    public ConnectionService(DatabaseAdapter adapter, DriverManagerService drivers) {
        this.adapter = adapter;
        this.drivers = drivers;
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "dm-connect-connector");
            thread.setDaemon(true);
            return thread;
        };
        connectorExecutor = Executors.newCachedThreadPool(factory);
    }

    public Connection connect(ConnectionProfile profile, char[] password) throws SQLException {
        return connect(profile, password, DEFAULT_TIMEOUT);
    }

    public Connection connect(ConnectionProfile profile, char[] password, Duration timeout) throws SQLException {
        AtomicBoolean expired = new AtomicBoolean(false);
        Future<Connection> future = connectorExecutor.submit(() -> {
            Connection connection = openConnection(profile, password);
            if (expired.get()) {
                connection.close();
                throw new SQLTimeoutException("连接已超时");
            }
            return connection;
        });
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            expired.set(true);
            future.cancel(true);
            throw new SQLTimeoutException("连接超时（" + timeout.toSeconds() + " 秒）");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new SQLException("连接过程被中断", exception);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof SQLException sqlException) throw sqlException;
            if (cause instanceof RuntimeException runtimeException) throw runtimeException;
            throw new SQLException("建立数据库连接失败", cause);
        }
    }

    public void testConnection(ConnectionProfile profile, char[] password) throws SQLException {
        try (Connection ignored = connect(profile, password)) {
            // 能建立并关闭连接即测试成功。
        }
    }

    private Connection openConnection(ConnectionProfile profile, char[] password) throws Exception {
        if (!adapter.id().equals(profile.databaseType())) {
            throw new IllegalArgumentException("当前版本不支持数据库类型：" + profile.databaseType());
        }
        Driver driver = drivers.driver(profile.driverId());
        String url = adapter.buildJdbcUrl(profile.host(), profile.port(), profile.advancedProperties());
        Properties properties = new Properties();
        properties.setProperty("user", profile.username());
        properties.setProperty("password", new String(password));
        try {
            Connection connection = driver.connect(url, properties);
            if (connection == null) throw new SQLException("JDBC 驱动不接受连接地址：" + url);
            return connection;
        } finally {
            properties.remove("password");
        }
    }

    @Override
    public void close() {
        connectorExecutor.shutdownNow();
    }
}
