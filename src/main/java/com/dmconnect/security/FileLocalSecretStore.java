package com.dmconnect.security;

import com.dmconnect.persistence.AppPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;

/** 将 32 字节密钥以 Base64 明文保存在 vault.key，权限尽量设为 600。 */
public final class FileLocalSecretStore implements LocalSecretStore {
    private final AppPaths paths;

    public FileLocalSecretStore(AppPaths paths) {
        this.paths = paths;
    }

    @Override
    public Optional<char[]> read() throws IOException, VaultException {
        Path keyFile = paths.vaultKeyFile();
        if (!Files.isRegularFile(keyFile)) return Optional.empty();
        String encoded = Files.readString(keyFile, StandardCharsets.UTF_8).strip();
        if (encoded.isEmpty()) return Optional.empty();
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new VaultException("本地加密存储密钥损坏", exception);
        }
        try {
            if (decoded.length != 32) throw new VaultException("本地加密存储密钥长度无效");
            return Optional.of(encoded.toCharArray());
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    @Override
    public void write(char[] secret) throws IOException {
        Path keyFile = paths.vaultKeyFile();
        Path temporary = Files.createTempFile(paths.root(), "vault-key-", ".tmp");
        try {
            Files.writeString(temporary, new String(secret) + System.lineSeparator(), StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
                // 当前平台不支持 POSIX 权限时使用默认文件权限。
            }
            try {
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, keyFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void delete() throws IOException {
        Files.deleteIfExists(paths.vaultKeyFile());
    }
}
