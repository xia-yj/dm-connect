package com.dmconnect.security;

import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VaultServiceTest {
    @TempDir
    Path temporary;

    @Test
    void automaticallyCreatesAndReopensEncryptedLocalStorage() throws Exception {
        AppPaths paths = paths();
        JsonStore store = new JsonStore();
        try (VaultService vault = new VaultService(paths, store)) {
            vault.open();
            vault.putSecret("profile-1", "数据库密码".toCharArray());
            vault.addHistory("profile-1", "测试连接", true, 18, "select * from 用户表");
            String persisted = Files.readString(paths.vaultFile());
            assertThat(persisted).doesNotContain("数据库密码", "select * from 用户表");
            assertThat(paths.vaultKeyFile()).exists();
        }

        try (VaultService vault = new VaultService(paths, store)) {
            vault.open();
            assertThat(new String(vault.getSecret("profile-1").orElseThrow())).isEqualTo("数据库密码");
            assertThat(vault.listHistory()).singleElement().satisfies(history -> {
                assertThat(history.profileName()).isEqualTo("测试连接");
                assertThat(history.sql()).isEqualTo("select * from 用户表");
            });
        }
    }

    @Test
    void rejectsTamperedEncryptedStorage() throws Exception {
        AppPaths paths = paths();
        JsonStore store = new JsonStore();
        try (VaultService vault = new VaultService(paths, store)) {
            vault.open();
        }

        VaultFile file = store.read(paths.vaultFile(), VaultFile.class);
        String ciphertext = file.verifier().ciphertext();
        char replacement = ciphertext.charAt(ciphertext.length() - 1) == 'A' ? 'B' : 'A';
        EncryptedPayload damaged = new EncryptedPayload(file.verifier().nonce(),
                ciphertext.substring(0, ciphertext.length() - 1) + replacement);
        store.writeAtomic(paths.vaultFile(), new VaultFile(file.version(), file.salt(), damaged,
                file.secrets(), file.history()));

        try (VaultService vault = new VaultService(paths, store)) {
            assertThatThrownBy(vault::open)
                    .isInstanceOf(VaultException.class)
                    .hasMessageContaining("损坏");
        }
    }

    @Test
    void keepsOnlyLatestThousandHistoryEntries() throws Exception {
        AppPaths paths = paths();
        try (VaultService vault = new VaultService(paths, new JsonStore())) {
            vault.open();
            for (int i = 0; i < 1002; i++) vault.addHistory("p", "P", true, i, "select " + i);
            assertThat(vault.listHistory()).hasSize(1000);
            assertThat(vault.listHistory().get(0).sql()).isEqualTo("select 1001");
            assertThat(vault.listHistory()).noneMatch(entry -> entry.sql().equals("select 0"));
        }
    }

    @Test
    void backsUpPasswordProtectedLegacyVaultInsteadOfDeletingIt() throws Exception {
        AppPaths paths = paths();
        String legacy = "{\"version\":1,\"legacy\":true}";
        Files.writeString(paths.vaultFile(), legacy);

        try (VaultService vault = new VaultService(paths, new JsonStore())) {
            vault.open();
            assertThat(vault.isUnlocked()).isTrue();
            assertThat(vault.legacyBackup()).contains(temporary.resolve("vault.master-password-backup.json"));
        }

        Path backup = temporary.resolve("vault.master-password-backup.json");
        assertThat(backup).exists();
        assertThat(Files.readString(backup)).isEqualTo(legacy);
        assertThat(paths.vaultFile()).exists();
        assertThat(paths.vaultKeyFile()).exists();
    }

    private AppPaths paths() throws Exception {
        AppPaths paths = new AppPaths(temporary, temporary.resolve("drivers"), temporary.resolve("profiles.json"),
                temporary.resolve("drivers.json"), temporary.resolve("vault.json"));
        paths.initialize();
        return paths;
    }
}
