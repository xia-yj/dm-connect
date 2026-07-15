package com.dmconnect.connection;

import com.dmconnect.database.mysql.MySqlDatabaseAdapter;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DriverManagerServiceTest {
    @TempDir
    Path temporary;

    @Test
    void importsValidIsolatedDriverAndChecksHash() throws Exception {
        AppPaths paths = new AppPaths(temporary.resolve("data"), temporary.resolve("data/drivers"),
                temporary.resolve("data/profiles.json"), temporary.resolve("data/drivers.json"),
                temporary.resolve("data/vault.json"));
        paths.initialize();
        Path jar = buildDriverJar();

        try (DriverManagerService service = new DriverManagerService(paths, new JsonStore())) {
            var descriptor = service.importDriver(jar);
            assertThat(descriptor.driverClass()).isEqualTo("dm.jdbc.driver.DmDriver");
            assertThat(descriptor.version()).isEqualTo("8.1");
            assertThat(Files.exists(Path.of(descriptor.storedPath()))).isTrue();
            assertThat(service.importDriver(jar).id()).isEqualTo(descriptor.id());

            Files.writeString(Path.of(descriptor.storedPath()), "tampered");
            assertThatThrownBy(() -> service.driver(descriptor.id()))
                    .isInstanceOf(SecurityException.class)
                    .hasMessageContaining("校验失败");
        }
    }

    @Test
    void importsMysqlConnectorJDriverWithTheMysqlAdapter() throws Exception {
        AppPaths paths = new AppPaths(temporary.resolve("mysql-data"), temporary.resolve("mysql-data/drivers"),
                temporary.resolve("mysql-data/profiles.json"), temporary.resolve("mysql-data/drivers.json"),
                temporary.resolve("mysql-data/vault.json"));
        paths.initialize();
        Path jar = buildMySqlDriverJar();

        try (DriverManagerService service = new DriverManagerService(paths, new JsonStore())) {
            var builtIn = service.findById(DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID).orElseThrow();
            assertThat(builtIn.driverClass()).isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(builtIn.storedPath()).startsWith("classpath:");
            assertThat(service.driver(DriverManagerService.BUILT_IN_MYSQL_DRIVER_ID).getClass().getName())
                    .isEqualTo("com.mysql.cj.jdbc.Driver");

            var descriptor = service.importDriver(jar, new MySqlDatabaseAdapter());

            assertThat(descriptor.driverClass()).isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(descriptor.version()).isEqualTo("9.7");
            assertThat(Files.exists(Path.of(descriptor.storedPath()))).isTrue();
            assertThat(service.driver(descriptor.id()).getClass().getName())
                    .isEqualTo("com.mysql.cj.jdbc.Driver");
            assertThat(service.importDriver(jar, new MySqlDatabaseAdapter()).id()).isEqualTo(descriptor.id());
        }
    }

    private Path buildDriverJar() throws Exception {
        Path jar = temporary.resolve("DmJdbcDriver.jar");
        String resource = "/dm/jdbc/driver/DmDriver.class";
        try (InputStream input = dm.jdbc.driver.DmDriver.class.getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            assertThat(input).isNotNull();
            output.putNextEntry(new JarEntry("dm/jdbc/driver/DmDriver.class"));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }

    private Path buildMySqlDriverJar() throws Exception {
        Path jar = temporary.resolve("mysql-connector-j.jar");
        String resource = "/com/mysql/cj/jdbc/Driver.class";
        try (InputStream input = com.mysql.cj.jdbc.Driver.class.getResourceAsStream(resource);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            assertThat(input).isNotNull();
            output.putNextEntry(new JarEntry("com/mysql/cj/jdbc/Driver.class"));
            input.transferTo(output);
            output.closeEntry();
        }
        return jar;
    }
}
