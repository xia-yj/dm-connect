package com.dmconnect.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MigratingSecretStoreTest {
    @TempDir
    Path temporary;

    @Test
    void migratesExistingFileSecretIntoPreferredStore() throws Exception {
        Path keyFile = temporary.resolve("vault.key");
        MemorySecretStore preferred = new MemorySecretStore();
        FileLocalSecretStore fileStore = fileStore();
        fileStore.write("c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2U=".toCharArray());

        try (var ignored = Files.newInputStream(keyFile)) {
            assertThat(keyFile).exists();
        }
        MigratingSecretStore store = new MigratingSecretStore(fileStore, preferred);
        Optional<char[]> secret = store.read();

        assertThat(secret).isPresent();
        assertThat(preferred.read()).isPresent();
        assertThat(keyFile).doesNotExist();
    }

    @Test
    void writesToFileWhenPreferredStoreFails() throws Exception {
        FileLocalSecretStore fileStore = fileStore();
        MigratingSecretStore store = new MigratingSecretStore(fileStore, new FailingSecretStore());
        char[] secret = "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2U=".toCharArray();
        store.write(secret);

        assertThat(fileStore.read()).isPresent();
        assertThat(temporary.resolve("vault.key")).exists();
    }

    private FileLocalSecretStore fileStore() {
        return new FileLocalSecretStore(new com.dmconnect.persistence.AppPaths(
                temporary, temporary.resolve("drivers"), temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"), temporary.resolve("vault.json")));
    }

    private static final class MemorySecretStore implements LocalSecretStore {
        private char[] secret;

        @Override
        public Optional<char[]> read() {
            return secret == null ? Optional.empty() : Optional.of(secret.clone());
        }

        @Override
        public void write(char[] next) {
            secret = next.clone();
        }

        @Override
        public void delete() {
            secret = null;
        }
    }

    private static final class FailingSecretStore implements LocalSecretStore {
        @Override
        public Optional<char[]> read() {
            throw new IllegalStateException("unavailable");
        }

        @Override
        public void write(char[] secret) {
            throw new IllegalStateException("unavailable");
        }

        @Override
        public void delete() {
            throw new IllegalStateException("unavailable");
        }
    }
}
