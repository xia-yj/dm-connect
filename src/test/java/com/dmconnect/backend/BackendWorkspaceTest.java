package com.dmconnect.backend;

import com.dmconnect.AppContext;
import com.dmconnect.model.ConnectionProfile;
import com.dmconnect.persistence.AppPaths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@ResourceLock(AppPaths.DATA_DIR_PROPERTY)
class BackendWorkspaceTest {
    @TempDir
    Path temporary;

    @Test
    void reusesAndReplacesTheSharedMetadataConnection() throws Exception {
        String previous = System.getProperty(AppPaths.DATA_DIR_PROPERTY);
        System.setProperty(AppPaths.DATA_DIR_PROPERTY, temporary.toString());
        try (AppContext context = new AppContext()) {
            Path database = temporary.resolve("workspace.db");
            ConnectionProfile profile = ConnectionProfile.create(
                    "sqlite", "local", "localhost", 1, database.toString(), "", "builtin:sqlite", Map.of(), false);
            BackendWorkspace workspace = new BackendWorkspace(context, profile, new char[0]);
            Connection[] seen = new Connection[3];
            try {
                workspace.withSharedConnection(connection -> {
                    seen[0] = connection;
                    return null;
                });
                workspace.withSharedConnection(connection -> {
                    seen[1] = connection;
                    return null;
                });
                assertThat(seen[1]).isSameAs(seen[0]);
                assertThat(seen[0].isClosed()).isFalse();

                seen[0].close();
                workspace.withSharedConnection(connection -> {
                    seen[2] = connection;
                    return null;
                });
                assertThat(seen[2]).isNotSameAs(seen[0]);
                assertThat(seen[2].isClosed()).isFalse();
            } finally {
                workspace.close();
            }
            assertThat(seen[2].isClosed()).isTrue();
        } finally {
            if (previous == null) System.clearProperty(AppPaths.DATA_DIR_PROPERTY);
            else System.setProperty(AppPaths.DATA_DIR_PROPERTY, previous);
        }
    }
}
