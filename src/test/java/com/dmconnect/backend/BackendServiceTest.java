package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ResourceLock(AppPaths.DATA_DIR_PROPERTY)
class BackendServiceTest {
    @TempDir
    Path temporary;

    @Test
    void bootstrapsWithAutomaticLocalStorageAndNoLoginRpc() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.toString());
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper)) {
                Map<?, ?> initial = (Map<?, ?>) service.handle("app.bootstrap", mapper.createObjectNode());
                assertThat(initial.get("version")).isEqualTo("2.0.0");
                assertThat(initial.get("profiles")).asList().isEmpty();
                assertThat(initial.get("drivers")).asList().hasSize(4);
                assertThat((java.util.List<?>) initial.get("drivers")).allSatisfy(item ->
                        assertThat(((Map<?, ?>) item).get("builtIn")).isEqualTo(true));
                assertThat(initial.containsKey("vault")).isFalse();
                assertThat(temporary.resolve("vault.json")).exists();
                assertThat(temporary.resolve("vault.key")).exists();
                assertThatThrownBy(() -> service.handle("vault.unlock",
                        mapper.createObjectNode().put("password", "unused")))
                        .isInstanceOfSatisfying(RpcException.class,
                                error -> assertThat(error.code()).isEqualTo("METHOD_NOT_FOUND"));
            }
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }

    @Test
    void rejectsUnknownMethodsAndAllowsProfileWritesWithoutLogin() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.toString());
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper)) {
                assertThatThrownBy(() -> service.handle("system.exec", mapper.createObjectNode()))
                        .isInstanceOfSatisfying(RpcException.class,
                                error -> assertThat(error.code()).isEqualTo("METHOD_NOT_FOUND"));

                ObjectNode profile = mapper.createObjectNode()
                        .put("name", "本地测试")
                        .put("host", "127.0.0.1")
                        .put("port", 5236)
                        .put("username", "SYSDBA")
                        .put("driverId", "driver-id");
                Map<?, ?> saved = (Map<?, ?>) service.handle("profile.save", profile);
                assertThat(saved.get("name")).isEqualTo("本地测试");
                assertThat(temporary.resolve("profiles.json")).exists();
            }
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }

    @Test
    void validatesNativeProfilesAndRejectsPlaintextCredentialProperties() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.toString());
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            try (BackendService service = new BackendService(context, mapper)) {
                ObjectNode mongo = mapper.createObjectNode()
                        .put("name", "Mongo 测试")
                        .put("databaseType", "mongo")
                        .put("host", "127.0.0.1")
                        .put("port", 27017)
                        .put("database", "app")
                        .put("username", "");
                mongo.putObject("advancedProperties").put("socketTimeoutMS", "15000");
                Map<?, ?> saved = (Map<?, ?>) service.handle("profile.save", mongo);
                assertThat(saved.get("driverId")).isEqualTo("builtin:mongo");

                ObjectNode misspelled = mongo.deepCopy();
                misspelled.withObject("/advancedProperties").removeAll().put("socketTimoutMS", "15000");
                assertThatThrownBy(() -> service.handle("profile.save", misspelled))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("socketTimoutMS");

                ObjectNode token = mapper.createObjectNode()
                        .put("name", "SQL Server 测试")
                        .put("databaseType", "sqlserver")
                        .put("host", "127.0.0.1")
                        .put("port", 1433)
                        .put("database", "app")
                        .put("username", "sa")
                        .put("driverId", "builtin:sqlserver");
                token.putObject("advancedProperties").put("accessToken", "must-not-persist");
                assertThatThrownBy(() -> service.handle("profile.save", token))
                        .isInstanceOfSatisfying(RpcException.class,
                                error -> assertThat(error.code()).isEqualTo("INVALID_ARGUMENT"));
            }
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }
}
