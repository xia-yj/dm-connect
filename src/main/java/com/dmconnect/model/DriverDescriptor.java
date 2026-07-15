package com.dmconnect.model;

import java.time.Instant;
import java.util.Objects;

public record DriverDescriptor(
        String id,
        String displayName,
        String storedPath,
        String sha256,
        String driverClass,
        String version,
        Instant importedAt) {

    public DriverDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(storedPath, "storedPath");
        Objects.requireNonNull(sha256, "sha256");
        Objects.requireNonNull(driverClass, "driverClass");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(importedAt, "importedAt");
    }

    @Override
    public String toString() {
        return displayName + "（" + version + "）";
    }
}
