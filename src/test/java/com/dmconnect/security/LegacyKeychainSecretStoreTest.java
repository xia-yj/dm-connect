package com.dmconnect.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyKeychainSecretStoreTest {
    private static final String FILE_SECRET = "c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2VjcmV0c2U=";
    private static final String KEYCHAIN_SECRET = "a2V5Y2hhaW5rZXljaGFpbmtleWNoYWlua2V5Y2hhaW4=";

    @TempDir
    Path temporary;

    @Test
    void prefersFileAndLeavesKeychainUntouched() throws Exception {
        FileLocalSecretStore fileStore = fileStore();
        fileStore.write(FILE_SECRET.toCharArray());
        MemorySecretStore keychain = new MemorySecretStore(KEYCHAIN_SECRET);

        Optional<char[]> secret = new LegacyKeychainSecretStore(fileStore, keychain).read();

        assertThat(secret).map(String::new).contains(FILE_SECRET);
        assertThat(keychain.read()).map(String::new).contains(KEYCHAIN_SECRET);
    }

    @Test
    void movesKeychainSecretIntoFileWhenFileIsMissing() throws Exception {
        FileLocalSecretStore fileStore = fileStore();
        MemorySecretStore keychain = new MemorySecretStore(KEYCHAIN_SECRET);

        Optional<char[]> secret = new LegacyKeychainSecretStore(fileStore, keychain).read();

        assertThat(secret).map(String::new).contains(KEYCHAIN_SECRET);
        assertThat(fileStore.read()).map(String::new).contains(KEYCHAIN_SECRET);
        assertThat(keychain.read()).isEmpty();
    }

    @Test
    void returnsEmptyWhenNeitherStoreHasSecret() throws Exception {
        assertThat(new LegacyKeychainSecretStore(fileStore(), new MemorySecretStore(null)).read()).isEmpty();
    }

    @Test
    void writesOnlyToFile() throws Exception {
        FileLocalSecretStore fileStore = fileStore();
        MemorySecretStore keychain = new MemorySecretStore(null);

        new LegacyKeychainSecretStore(fileStore, keychain).write(FILE_SECRET.toCharArray());

        assertThat(fileStore.read()).map(String::new).contains(FILE_SECRET);
        assertThat(keychain.read()).isEmpty();
    }

    @Test
    void failsInsteadOfReportingNoSecretWhenKeychainIsUnreadable() {
        LegacyKeychainSecretStore store = new LegacyKeychainSecretStore(fileStore(), new UnreadableSecretStore());

        assertThatThrownBy(store::read).isInstanceOf(VaultException.class);
        assertThat(temporary.resolve("vault.key")).doesNotExist();
    }

    private FileLocalSecretStore fileStore() {
        return new FileLocalSecretStore(new com.dmconnect.persistence.AppPaths(
                temporary, temporary.resolve("drivers"), temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"), temporary.resolve("vault.json")));
    }

    private static final class MemorySecretStore implements LocalSecretStore {
        private char[] secret;

        MemorySecretStore(String initial) {
            secret = initial == null ? null : initial.toCharArray();
        }

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

    private static final class UnreadableSecretStore implements LocalSecretStore {
        @Override
        public Optional<char[]> read() throws VaultException {
            throw new VaultException("无法读取 macOS 钥匙串：locked");
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
