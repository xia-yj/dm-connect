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
                assertThat(initial.get("drivers")).asList().isEmpty();
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
}
