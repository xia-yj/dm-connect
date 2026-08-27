package com.dmconnect.security;

import java.io.IOException;
import java.util.Optional;

/** 优先使用系统保险箱；若只有旧的 vault.key，则迁移后删除明文文件。 */
public final class MigratingSecretStore implements LocalSecretStore {
    private final LocalSecretStore fileStore;
    private final LocalSecretStore preferredStore;

    public MigratingSecretStore(LocalSecretStore fileStore, LocalSecretStore preferredStore) {
        this.fileStore = fileStore;
        this.preferredStore = preferredStore;
    }

    @Override
    public Optional<char[]> read() throws IOException, VaultException {
        Optional<char[]> preferred;
        try {
            preferred = preferredStore.read();
        } catch (VaultException exception) {
            Optional<char[]> file = fileStore.read();
            if (file.isPresent()) return file;
            throw exception;
        }
        if (preferred.isPresent()) return preferred;
        Optional<char[]> file = fileStore.read();
        if (file.isEmpty()) return Optional.empty();
        try {
            preferredStore.write(file.get());
            fileStore.delete();
        } catch (Exception ignored) {
            // 系统保险箱不可用时继续使用 vault.key。
        }
        return file;
    }

    @Override
    public void write(char[] secret) throws IOException, VaultException {
        try {
            preferredStore.write(secret);
            fileStore.delete();
        } catch (Exception exception) {
            fileStore.write(secret);
        }
    }

    @Override
    public void delete() throws IOException {
        try {
            preferredStore.delete();
        } catch (Exception ignored) {
            // 继续删除文件回退副本。
        }
        fileStore.delete();
    }
}
