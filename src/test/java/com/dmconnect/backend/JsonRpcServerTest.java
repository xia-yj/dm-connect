package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock(AppPaths.DATA_DIR_PROPERTY)
class JsonRpcServerTest {
    @TempDir
    Path temporary;

    @Test
    void writesExactlyOneJsonLinePerResponse() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.toString());
        try (AppContext context = new AppContext()) {
            ObjectMapper mapper = new JsonStore().mapper();
            CountDownLatch response = new CountDownLatch(1);
            ByteArrayOutputStream output = new ByteArrayOutputStream() {
                @Override
                public synchronized void flush() {
                    response.countDown();
                }
            };
            byte[] request = "{\"id\":\"1\",\"method\":\"app.ping\",\"params\":{}}\n"
                    .getBytes(StandardCharsets.UTF_8);
            try (BackendService service = new BackendService(context, mapper);
                 JsonRpcServer server = new JsonRpcServer(service, mapper,
                         new ByteArrayInputStream(request), output)) {
                server.run();
                assertThat(response.await(5, TimeUnit.SECONDS)).isTrue();
                String payload = output.toString(StandardCharsets.UTF_8);
                assertThat(payload.lines()).hasSize(1);
                assertThat(mapper.readTree(payload).path("result").path("version").asText()).isEqualTo("2.0.0");
            }
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }
}
