package com.dmconnect;

import com.dmconnect.connection.ConnectionService;
import com.dmconnect.connection.DriverManagerService;
import com.dmconnect.database.dm.DmDatabaseAdapter;
import com.dmconnect.database.mysql.MySqlDatabaseAdapter;
import com.dmconnect.nativeclient.NativeDatabaseAdapter;
import com.dmconnect.database.generic.GenericJdbcAdapter;
import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.dmconnect.persistence.ProfileRepository;
import com.dmconnect.security.VaultService;
import com.dmconnect.security.VaultException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public final class AppContext implements AutoCloseable {
    private final AppPaths paths;
    private final JsonStore jsonStore;
    private final ProfileRepository profiles;
    private final VaultService vault;
    private final DriverManagerService drivers;
    private final Map<String, DatabaseAdapter> databaseAdapters;
    private final ConnectionService connections;
    private final ExecutorService executor;

    public AppContext() throws IOException, VaultException {
        paths = AppPaths.defaultPaths();
        paths.initialize();
        jsonStore = new JsonStore();
        profiles = new ProfileRepository(paths, jsonStore);
        vault = new VaultService(paths, jsonStore);
        vault.open();
        drivers = new DriverManagerService(paths, jsonStore);
        Map<String, DatabaseAdapter> adapters = new LinkedHashMap<>();
        DatabaseAdapter dm = new DmDatabaseAdapter();
        DatabaseAdapter mysql = new MySqlDatabaseAdapter();
        DatabaseAdapter mongo = new NativeDatabaseAdapter("mongo", "MongoDB", 27017, "native:mongodb");
        DatabaseAdapter redis = new NativeDatabaseAdapter("redis", "Redis", 6379, "native:redis");
        DatabaseAdapter postgresql = new GenericJdbcAdapter("postgresql", "PostgreSQL", "org.postgresql.Driver", 5432, GenericJdbcAdapter.Flavor.POSTGRESQL);
        DatabaseAdapter oracle = new GenericJdbcAdapter("oracle", "Oracle", "oracle.jdbc.OracleDriver", 1521, GenericJdbcAdapter.Flavor.ORACLE);
        DatabaseAdapter sqlserver = new GenericJdbcAdapter("sqlserver", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", 1433, GenericJdbcAdapter.Flavor.SQLSERVER);
        DatabaseAdapter sqlite = new GenericJdbcAdapter("sqlite", "SQLite", "org.sqlite.JDBC", 1, GenericJdbcAdapter.Flavor.SQLITE);
        adapters.put(dm.id(), dm);
        adapters.put(mysql.id(), mysql);
        adapters.put(mongo.id(), mongo);
        adapters.put(redis.id(), redis);
        adapters.put(postgresql.id(), postgresql);
        adapters.put(oracle.id(), oracle);
        adapters.put(sqlserver.id(), sqlserver);
        adapters.put(sqlite.id(), sqlite);
        databaseAdapters = Map.copyOf(adapters);
        connections = new ConnectionService(databaseAdapters, drivers);
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "dm-connect-worker-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(Math.max(4, Runtime.getRuntime().availableProcessors() / 2), factory);
    }

    public AppPaths paths() {
        return paths;
    }

    public ProfileRepository profiles() {
        return profiles;
    }

    public VaultService vault() {
        return vault;
    }

    public DriverManagerService drivers() {
        return drivers;
    }

    public DatabaseAdapter databaseAdapter() {
        return databaseAdapter("dm");
    }

    public DatabaseAdapter databaseAdapter(String databaseType) {
        DatabaseAdapter adapter = databaseAdapters.get(databaseType);
        if (adapter == null) throw new IllegalArgumentException("当前版本不支持数据库类型：" + databaseType);
        return adapter;
    }

    public Map<String, DatabaseAdapter> databaseAdapters() {
        return databaseAdapters;
    }

    public ConnectionService connections() {
        return connections;
    }

    public ExecutorService executor() {
        return executor;
    }

    @Override
    public void close() {
        executor.shutdownNow();
        connections.close();
        drivers.close();
        vault.close();
    }
}
