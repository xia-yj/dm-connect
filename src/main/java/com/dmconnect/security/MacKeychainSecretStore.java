package com.dmconnect.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class MacKeychainSecretStore implements LocalSecretStore {
    static final String SERVICE = "com.dmconnect.vault";
    static final String ACCOUNT = "local-store";

    @Override
    public Optional<char[]> read() throws IOException, VaultException {
        ProcessResult result = run(5, "security", "find-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-w");
        if (result.exitCode != 0) {
            String detail = (result.stderr + " " + result.stdout).toLowerCase();
            if (result.exitCode == 44 || detail.contains("could not be found")) return Optional.empty();
            throw new VaultException("无法读取 macOS 钥匙串：" + result.stderr.strip());
        }
        String encoded = result.stdout.strip();
        return encoded.isEmpty() ? Optional.empty() : Optional.of(encoded.toCharArray());
    }

    @Override
    public void write(char[] secret) throws IOException, VaultException {
        ProcessResult result = run(8, "security", "add-generic-password", "-a", ACCOUNT, "-s", SERVICE, "-U",
                "-w", new String(secret));
        if (result.exitCode != 0) {
            throw new VaultException("无法写入 macOS 钥匙串：" + result.stderr.strip());
        }
    }

    @Override
    public void delete() throws IOException {
        run(5, "security", "delete-generic-password", "-a", ACCOUNT, "-s", SERVICE);
    }

    private static ProcessResult run(int timeoutSeconds, String... command) throws IOException {
        Process process = new ProcessBuilder(command).redirectErrorStream(false).start();
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("钥匙串命令超时");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("钥匙串命令被中断", exception);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
