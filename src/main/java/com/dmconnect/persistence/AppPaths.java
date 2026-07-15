package com.dmconnect.persistence;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record AppPaths(
        Path root,
        Path driversDirectory,
        Path profilesFile,
        Path driversFile,
        Path vaultFile) {

    public static final String DATA_DIR_PROPERTY = "dmconnect.dataDir";

    public static AppPaths defaultPaths() {
        String override = System.getProperty(DATA_DIR_PROPERTY);
        Path root = override == null || override.isBlank()
                ? Path.of(System.getProperty("user.home"), "Library", "Application Support", "DM Connect")
                : Path.of(override);
        return new AppPaths(root, root.resolve("drivers"), root.resolve("profiles.json"),
                root.resolve("drivers.json"), root.resolve("vault.json"));
    }

    public void initialize() throws IOException {
        Files.createDirectories(root);
        Files.createDirectories(driversDirectory);
    }

    public Path vaultKeyFile() {
        return root.resolve("vault.key");
    }
}
