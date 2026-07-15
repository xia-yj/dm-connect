package com.dmconnect.security;

import com.dmconnect.model.HistoryEntry;
import com.dmconnect.persistence.AppPaths;
import com.dmconnect.persistence.JsonStore;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** 由应用自动管理密钥的 Argon2id + AES-256-GCM 本地加密存储。 */
public final class VaultService implements AutoCloseable {
    public static final int MAX_HISTORY = 1000;
    private static final int VERSION = 2;
    private static final int ARGON_MEMORY_KB = 65_536;
    private static final int ARGON_ITERATIONS = 3;
    private static final int ARGON_PARALLELISM = 1;
    private static final byte[] VERIFIER_TEXT = "DM_CONNECT_LOCAL_STORE_V2".getBytes(StandardCharsets.UTF_8);
    private static final String VERIFIER_AAD = "vault-verifier";

    private final AppPaths paths;
    private final JsonStore store;
    private final SecureRandom random = new SecureRandom();
    private byte[] keyBytes;
    private VaultFile vault;
    private Path legacyBackup;

    public VaultService(AppPaths paths, JsonStore store) {
        this.paths = paths;
        this.store = store;
    }

    public synchronized boolean exists() {
        return Files.isRegularFile(paths.vaultFile());
    }

    public synchronized boolean isUnlocked() {
        return keyBytes != null && vault != null;
    }

    public synchronized Optional<Path> legacyBackup() {
        return Optional.ofNullable(legacyBackup);
    }

    /** 启动时自动创建或打开本机加密存储，不需要用户密码。 */
    public synchronized void open() throws IOException, VaultException {
        if (isUnlocked()) return;
        if (!Files.isRegularFile(paths.vaultKeyFile())) {
            if (exists()) backupLegacyVault();
            createLocalStore();
            return;
        }

        char[] localSecret = readLocalSecret();
        try {
            if (exists()) unlockWithSecret(localSecret);
            else createWithSecret(localSecret);
        } finally {
            Arrays.fill(localSecret, '\0');
        }
    }

    private void createLocalStore() throws IOException, VaultException {
        byte[] randomSecret = randomBytes(32);
        char[] localSecret = encode(randomSecret).toCharArray();
        Arrays.fill(randomSecret, (byte) 0);
        try {
            writeLocalSecret(localSecret);
            createWithSecret(localSecret);
        } finally {
            Arrays.fill(localSecret, '\0');
        }
    }

    private void createWithSecret(char[] localSecret) throws IOException, VaultException {
        if (exists()) throw new VaultException("本地加密存储已经存在");
        byte[] salt = randomBytes(16);
        byte[] derived = deriveKey(localSecret, salt);
        try {
            keyBytes = derived;
            EncryptedPayload verifier = encrypt(VERIFIER_TEXT, VERIFIER_AAD);
            vault = new VaultFile(VERSION, encode(salt), verifier, Map.of(), List.of());
            persist();
        } catch (GeneralSecurityException exception) {
            clearKey();
            vault = null;
            throw new VaultException("创建本地加密存储失败", exception);
        } catch (IOException exception) {
            clearKey();
            vault = null;
            throw exception;
        } finally {
            Arrays.fill(salt, (byte) 0);
        }
    }

    private void unlockWithSecret(char[] localSecret) throws IOException, VaultException {
        if (!exists()) throw new VaultException("本地加密存储不存在");
        VaultFile loaded = store.read(paths.vaultFile(), VaultFile.class);
        if (loaded.version() != VERSION) throw new VaultException("不支持的本地加密存储版本：" + loaded.version());
        byte[] salt;
        try {
            salt = decode(loaded.salt());
        } catch (IllegalArgumentException exception) {
            throw new VaultException("本地加密存储盐值损坏", exception);
        }
        byte[] derived = deriveKey(localSecret, salt);
        Arrays.fill(salt, (byte) 0);
        clearKey();
        keyBytes = derived;
        vault = loaded;
        try {
            byte[] verifier = decrypt(loaded.verifier(), VERIFIER_AAD);
            boolean valid = Arrays.equals(verifier, VERIFIER_TEXT);
            Arrays.fill(verifier, (byte) 0);
            if (!valid) {
                clearKey();
                vault = null;
                throw new VaultException("本地加密存储已损坏");
            }
        } catch (AEADBadTagException exception) {
            clearKey();
            vault = null;
            throw new VaultException("本地加密存储密钥无效或数据已损坏");
        } catch (GeneralSecurityException exception) {
            clearKey();
            vault = null;
            throw new VaultException("无法打开本地加密存储", exception);
        }
    }

    public synchronized void putSecret(String profileId, char[] password) throws IOException, VaultException {
        requireUnlocked();
        byte[] plaintext = new String(password).getBytes(StandardCharsets.UTF_8);
        try {
            Map<String, EncryptedPayload> secrets = new LinkedHashMap<>(vault.secrets());
            secrets.put(profileId, encrypt(plaintext, "secret:" + profileId));
            vault = new VaultFile(vault.version(), vault.salt(), vault.verifier(), secrets, vault.history());
            persist();
        } catch (GeneralSecurityException exception) {
            throw new VaultException("保存连接密码失败", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized Optional<char[]> getSecret(String profileId) throws VaultException {
        requireUnlocked();
        EncryptedPayload payload = vault.secrets().get(profileId);
        if (payload == null) return Optional.empty();
        byte[] plaintext = null;
        try {
            plaintext = decrypt(payload, "secret:" + profileId);
            return Optional.of(new String(plaintext, StandardCharsets.UTF_8).toCharArray());
        } catch (GeneralSecurityException exception) {
            throw new VaultException("连接密码密文已损坏", exception);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized void removeSecret(String profileId) throws IOException, VaultException {
        requireUnlocked();
        Map<String, EncryptedPayload> secrets = new LinkedHashMap<>(vault.secrets());
        if (secrets.remove(profileId) != null) {
            vault = new VaultFile(vault.version(), vault.salt(), vault.verifier(), secrets, vault.history());
            persist();
        }
    }

    public synchronized HistoryEntry addHistory(String profileId, String profileName, boolean success,
                                                long durationMillis, String sql) throws IOException, VaultException {
        requireUnlocked();
        String id = UUID.randomUUID().toString();
        byte[] plaintext = sql.getBytes(StandardCharsets.UTF_8);
        try {
            EncryptedHistoryRecord encrypted = new EncryptedHistoryRecord(id, profileId, profileName,
                    Instant.now(), success, durationMillis, encrypt(plaintext, "history:" + id));
            List<EncryptedHistoryRecord> history = new ArrayList<>(vault.history());
            history.add(encrypted);
            if (history.size() > MAX_HISTORY) {
                history = new ArrayList<>(history.subList(history.size() - MAX_HISTORY, history.size()));
            }
            vault = new VaultFile(vault.version(), vault.salt(), vault.verifier(), vault.secrets(), history);
            persist();
            return decryptHistory(encrypted);
        } catch (GeneralSecurityException exception) {
            throw new VaultException("保存 SQL 历史失败", exception);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public synchronized List<HistoryEntry> listHistory() throws VaultException {
        requireUnlocked();
        List<HistoryEntry> result = new ArrayList<>(vault.history().size());
        for (EncryptedHistoryRecord record : vault.history()) result.add(decryptHistory(record));
        result.sort(Comparator.comparing(HistoryEntry::executedAt).reversed());
        return List.copyOf(result);
    }

    public synchronized void deleteHistory(String id) throws IOException, VaultException {
        requireUnlocked();
        List<EncryptedHistoryRecord> history = new ArrayList<>(vault.history());
        if (history.removeIf(record -> record.id().equals(id))) {
            vault = new VaultFile(vault.version(), vault.salt(), vault.verifier(), vault.secrets(), history);
            persist();
        }
    }

    public synchronized void clearHistory() throws IOException, VaultException {
        requireUnlocked();
        vault = new VaultFile(vault.version(), vault.salt(), vault.verifier(), vault.secrets(), List.of());
        persist();
    }

    public synchronized void resetLocalData() throws IOException, VaultException {
        lock();
        Files.deleteIfExists(paths.vaultFile());
        Files.deleteIfExists(paths.vaultKeyFile());
        createLocalStore();
    }

    private synchronized void lock() {
        clearKey();
        vault = null;
    }

    private HistoryEntry decryptHistory(EncryptedHistoryRecord record) throws VaultException {
        byte[] plaintext = null;
        try {
            plaintext = decrypt(record.sql(), "history:" + record.id());
            return new HistoryEntry(record.id(), record.profileId(), record.profileName(), record.executedAt(),
                    record.success(), record.durationMillis(), new String(plaintext, StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new VaultException("SQL 历史密文已损坏：" + record.id(), exception);
        } finally {
            if (plaintext != null) Arrays.fill(plaintext, (byte) 0);
        }
    }

    private EncryptedPayload encrypt(byte[] plaintext, String aad) throws GeneralSecurityException {
        requireKey();
        byte[] nonce = randomBytes(12);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        byte[] ciphertext = cipher.doFinal(plaintext);
        try {
            return new EncryptedPayload(encode(nonce), encode(ciphertext));
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private byte[] decrypt(EncryptedPayload payload, String aad) throws GeneralSecurityException {
        requireKey();
        byte[] nonce = decode(payload.nonce());
        byte[] ciphertext = decode(payload.ciphertext());
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(ciphertext);
        } finally {
            Arrays.fill(nonce, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static byte[] deriveKey(char[] password, byte[] salt) {
        Argon2Parameters parameters = new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withSalt(salt)
                .withMemoryAsKB(ARGON_MEMORY_KB)
                .withIterations(ARGON_ITERATIONS)
                .withParallelism(ARGON_PARALLELISM)
                .build();
        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] output = new byte[32];
        generator.generateBytes(password, output);
        return output;
    }

    private void requireUnlocked() throws VaultException {
        if (!isUnlocked()) throw new VaultException("本地加密存储尚未就绪");
    }

    private void requireKey() {
        if (keyBytes == null) throw new IllegalStateException("本地加密存储尚未就绪");
    }

    private void persist() throws IOException {
        store.writeAtomic(paths.vaultFile(), vault);
    }

    private byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

    private static String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static byte[] decode(String encoded) {
        return Base64.getDecoder().decode(encoded);
    }

    private char[] readLocalSecret() throws IOException, VaultException {
        String encoded = Files.readString(paths.vaultKeyFile(), StandardCharsets.UTF_8).strip();
        byte[] decoded;
        try {
            decoded = decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new VaultException("本地加密存储密钥损坏", exception);
        }
        try {
            if (decoded.length != 32) throw new VaultException("本地加密存储密钥长度无效");
            return encoded.toCharArray();
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
    }

    private void writeLocalSecret(char[] localSecret) throws IOException {
        Path keyFile = paths.vaultKeyFile();
        Path temporary = Files.createTempFile(paths.root(), "vault-key-", ".tmp");
        try {
            Files.writeString(temporary, new String(localSecret) + System.lineSeparator(), StandardCharsets.UTF_8);
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

    private void backupLegacyVault() throws IOException {
        Path backup = paths.root().resolve("vault.master-password-backup.json");
        int suffix = 1;
        while (Files.exists(backup)) {
            backup = paths.root().resolve("vault.master-password-backup-" + suffix++ + ".json");
        }
        Files.move(paths.vaultFile(), backup);
        legacyBackup = backup;
    }

    private void clearKey() {
        if (keyBytes != null) Arrays.fill(keyBytes, (byte) 0);
        keyBytes = null;
    }

    @Override
    public void close() {
        lock();
    }
}
