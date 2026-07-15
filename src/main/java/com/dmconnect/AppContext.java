package com.dmconnect;

import com.dmconnect.connection.ConnectionService;
import com.dmconnect.connection.DriverManagerService;
import com.dmconnect.database.dm.DmDatabaseAdapter;
import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.dmconnect.persistence.ProfileRepository;
import com.dmconnect.security.VaultService;
import com.dmconnect.security.VaultException;

import java.io.IOException;
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
    private final DatabaseAdapter databaseAdapter;
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
        databaseAdapter = new DmDatabaseAdapter();
        connections = new ConnectionService(databaseAdapter, drivers);
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
        return databaseAdapter;
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
