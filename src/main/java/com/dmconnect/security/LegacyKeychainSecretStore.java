package com.dmconnect.security;

import java.io.IOException;
import java.util.Optional;

/** 只使用 vault.key；旧版本曾把密钥放进钥匙串，vault.key 不存在时搬回文件并删除钥匙串条目。 */
public final class LegacyKeychainSecretStore implements LocalSecretStore {
    private final LocalSecretStore fileStore;
    private final LocalSecretStore keychain;

    public LegacyKeychainSecretStore(LocalSecretStore fileStore, LocalSecretStore keychain) {
        this.fileStore = fileStore;
        this.keychain = keychain;
    }

    @Override
    public Optional<char[]> read() throws IOException, VaultException {
        Optional<char[]> file = fileStore.read();
        if (file.isPresent()) return file;
        // 钥匙串读取失败时直接抛出，避免把仍能解密的旧数据当成无主文件改名备份。
        Optional<char[]> legacy = keychain.read();
        if (legacy.isEmpty()) return Optional.empty();
        fileStore.write(legacy.get());
        try {
            keychain.delete();
        } catch (Exception ignored) {
            // 密钥已写入 vault.key，残留的钥匙串条目不会再被读取。
        }
        return legacy;
    }

    @Override
    public void write(char[] secret) throws IOException, VaultException {
        fileStore.write(secret);
    }

    @Override
    public void delete() throws IOException {
        fileStore.delete();
    }
}
