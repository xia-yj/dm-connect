package com.dmconnect.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class WindowsDpapiSecretStore implements LocalSecretStore {
    private final Path protectedFile;

    WindowsDpapiSecretStore(Path protectedFile) {
        this.protectedFile = protectedFile;
    }

    @Override
    public Optional<char[]> read() throws IOException, VaultException {
        if (!Files.isRegularFile(protectedFile)) return Optional.empty();
        ProcessResult result = run("""
                Add-Type -AssemblyName System.Security
                $bytes = [IO.File]::ReadAllBytes($env:DMCONNECT_VAULT_DPAPI)
                $plain = [Security.Cryptography.ProtectedData]::Unprotect($bytes, $null, 'CurrentUser')
                [Console]::Out.Write([Text.Encoding]::UTF8.GetString($plain))
                """);
        if (result.exitCode != 0) {
            throw new VaultException("无法读取 Windows DPAPI 密钥：" + result.stderr.strip());
        }
        String encoded = result.stdout.strip();
        return encoded.isEmpty() ? Optional.empty() : Optional.of(encoded.toCharArray());
    }

    @Override
    public void write(char[] secret) throws IOException, VaultException {
        Files.createDirectories(protectedFile.toAbsolutePath().getParent());
        ProcessResult result = run("""
                Add-Type -AssemblyName System.Security
                $plain = [Text.Encoding]::UTF8.GetBytes($env:DMCONNECT_VAULT_SECRET)
                $protected = [Security.Cryptography.ProtectedData]::Protect($plain, $null, 'CurrentUser')
                [IO.File]::WriteAllBytes($env:DMCONNECT_VAULT_DPAPI, $protected)
                """, secret);
        if (result.exitCode != 0) {
            throw new VaultException("无法写入 Windows DPAPI 密钥：" + result.stderr.strip());
        }
    }

    @Override
    public void delete() throws IOException {
        Files.deleteIfExists(protectedFile);
    }

    private ProcessResult run(String script) throws IOException {
        return run(script, null);
    }

    private ProcessResult run(String script, char[] secret) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command", script);
        builder.environment().put("DMCONNECT_VAULT_DPAPI", protectedFile.toAbsolutePath().toString());
        if (secret != null) builder.environment().put("DMCONNECT_VAULT_SECRET", new String(secret));
        Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException("DPAPI 命令超时");
            }
            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            return new ProcessResult(process.exitValue(), stdout, stderr);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("DPAPI 命令被中断", exception);
        }
    }

    private record ProcessResult(int exitCode, String stdout, String stderr) {
    }
}
