package com.dmconnect.connection;

import com.dmconnect.database.dm.DmDatabaseAdapter;
import com.dmconnect.database.spi.DatabaseAdapter;
import com.dmconnect.model.DriverDescriptor;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Driver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class DriverManagerService implements AutoCloseable {
    static final String BUILT_IN_MYSQL_DRIVER_ID = "builtin:mysql-connector-j";
    private static final TypeReference<List<DriverDescriptor>> DRIVER_LIST = new TypeReference<>() {
    };
    private final AppPaths paths;
    private final JsonStore store;
    private final Map<String, DriverRuntime> loaded = new ConcurrentHashMap<>();

    public DriverManagerService(AppPaths paths, JsonStore store) {
        this.paths = paths;
        this.store = store;
    }

    public synchronized List<DriverDescriptor> findAll() throws IOException {
        List<DriverDescriptor> descriptors = Files.exists(paths.driversFile())
                ? new ArrayList<>(store.read(paths.driversFile(), DRIVER_LIST))
                : new ArrayList<>();
        descriptors.removeIf(driver -> driver.id().equals(BUILT_IN_MYSQL_DRIVER_ID));
        descriptors.add(builtInMysqlDescriptor());
        descriptors.sort(Comparator.comparing(DriverDescriptor::importedAt).reversed());
        return List.copyOf(descriptors);
    }

    public synchronized Optional<DriverDescriptor> findById(String id) throws IOException {
        return findAll().stream().filter(driver -> driver.id().equals(id)).findFirst();
    }

    public synchronized DriverDescriptor importDriver(Path sourceJar) throws Exception {
        return importDriver(sourceJar, new DmDatabaseAdapter());
    }

    public synchronized DriverDescriptor importDriver(Path sourceJar, DatabaseAdapter adapter) throws Exception {
        if (sourceJar == null || !Files.isRegularFile(sourceJar)) {
            throw new IllegalArgumentException("请选择有效的 JDBC JAR 文件");
        }
        if (adapter == null) throw new IllegalArgumentException("未指定数据库类型");
        String sha256 = sha256(sourceJar);
        Optional<DriverDescriptor> existing = findAll().stream()
                .filter(driver -> driver.sha256().equalsIgnoreCase(sha256))
                .filter(driver -> driver.driverClass().equals(adapter.driverClassName())).findFirst();
        if (existing.isPresent()) return existing.get();

        DriverRuntime inspected = loadRuntime(sourceJar, adapter.driverClassName());
        String version;
        try {
            Driver driver = inspected.driver();
            version = driver.getMajorVersion() + "." + driver.getMinorVersion();
        } finally {
            inspected.close();
        }

        Path target = paths.driversDirectory().resolve(sha256 + ".jar");
        Files.copy(sourceJar, target, StandardCopyOption.REPLACE_EXISTING);
        DriverDescriptor descriptor = new DriverDescriptor(
                UUID.randomUUID().toString(),
                sourceJar.getFileName().toString(),
                target.toAbsolutePath().toString(),
                sha256,
                adapter.driverClassName(),
                version,
                Instant.now());
        List<DriverDescriptor> descriptors = new ArrayList<>(findAll());
        descriptors.add(descriptor);
        store.writeAtomic(paths.driversFile(), descriptors);
        return descriptor;
    }

    public Driver driver(String descriptorId) throws Exception {
        DriverRuntime runtime = loaded.get(descriptorId);
        if (runtime != null) return runtime.driver();
        synchronized (loaded) {
            runtime = loaded.get(descriptorId);
            if (runtime != null) return runtime.driver();
            if (BUILT_IN_MYSQL_DRIVER_ID.equals(descriptorId)) {
                runtime = new DriverRuntime(new com.mysql.cj.jdbc.Driver());
                loaded.put(descriptorId, runtime);
                return runtime.driver();
            }
            DriverDescriptor descriptor = findById(descriptorId)
                    .orElseThrow(() -> new IllegalArgumentException("找不到指定的 JDBC 驱动"));
            Path jar = Path.of(descriptor.storedPath());
            if (!Files.isRegularFile(jar)) throw new IOException("JDBC 驱动文件不存在：" + jar);
            String actualHash = sha256(jar);
            if (!actualHash.equalsIgnoreCase(descriptor.sha256())) {
                throw new SecurityException("JDBC 驱动校验失败，文件可能已被替换");
            }
            runtime = loadRuntime(jar, descriptor.driverClass());
            loaded.put(descriptorId, runtime);
            return runtime.driver();
        }
    }

    private static DriverDescriptor builtInMysqlDescriptor() {
        String version;
        try {
            com.mysql.cj.jdbc.Driver driver = new com.mysql.cj.jdbc.Driver();
            version = driver.getMajorVersion() + "." + driver.getMinorVersion();
        } catch (java.sql.SQLException ignored) {
            version = "8.3";
        }
        return new DriverDescriptor(
                BUILT_IN_MYSQL_DRIVER_ID,
                "MySQL Connector/J（应用内置）",
                "classpath:com.mysql.cj.jdbc.Driver",
                "builtin:mysql-connector-j",
                "com.mysql.cj.jdbc.Driver",
                version,
                Instant.MAX);
    }

    private static DriverRuntime loadRuntime(Path jar, String driverClass) throws Exception {
        URLClassLoader classLoader = new URLClassLoader(
                new URL[]{jar.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
        try {
            Class<?> type = Class.forName(driverClass, true, classLoader);
            Object instance = type.getDeclaredConstructor().newInstance();
            if (!(instance instanceof Driver driver)) {
                throw new IllegalArgumentException(driverClass + " 未实现 java.sql.Driver");
            }
            return new DriverRuntime(driver, classLoader);
        } catch (Exception | LinkageError exception) {
            classLoader.close();
            throw new IllegalArgumentException("无法加载 JDBC 驱动 " + driverClass + "，请确认 JAR 与 Java 17 兼容", exception);
        }
    }

    public static String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) >= 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("当前 Java 运行时不支持 SHA-256", impossible);
        }
    }

    @Override
    public void close() {
        loaded.values().forEach(runtime -> {
            try {
                runtime.close();
            } catch (Exception ignored) {
                // 应用退出时尽力释放驱动类加载器。
            }
        });
        loaded.clear();
    }
}
