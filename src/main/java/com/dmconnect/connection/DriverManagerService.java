package com.dmconnect.connection;

import com.dmconnect.database.dm.DmDatabaseAdapter;
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
        if (!Files.exists(paths.driversFile())) return List.of();
        List<DriverDescriptor> descriptors = new ArrayList<>(store.read(paths.driversFile(), DRIVER_LIST));
        descriptors.sort(Comparator.comparing(DriverDescriptor::importedAt).reversed());
        return List.copyOf(descriptors);
    }

    public synchronized Optional<DriverDescriptor> findById(String id) throws IOException {
        return findAll().stream().filter(driver -> driver.id().equals(id)).findFirst();
    }

    public synchronized DriverDescriptor importDriver(Path sourceJar) throws Exception {
        if (sourceJar == null || !Files.isRegularFile(sourceJar)) {
            throw new IllegalArgumentException("请选择有效的 JDBC JAR 文件");
        }
        String sha256 = sha256(sourceJar);
        Optional<DriverDescriptor> existing = findAll().stream()
                .filter(driver -> driver.sha256().equalsIgnoreCase(sha256)).findFirst();
        if (existing.isPresent()) return existing.get();

        DriverRuntime inspected = loadRuntime(sourceJar, DmDatabaseAdapter.DRIVER_CLASS);
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
                DmDatabaseAdapter.DRIVER_CLASS,
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
            throw new IllegalArgumentException("无法加载达梦 JDBC 驱动，请确认 JAR 与 Java 17 兼容", exception);
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
