package com.dmconnect.security;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record VaultFile(
        int version,
        String salt,
        EncryptedPayload verifier,
        Map<String, EncryptedPayload> secrets,
        List<EncryptedHistoryRecord> history) {

    public VaultFile {
        secrets = secrets == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(secrets));
        history = history == null ? List.of() : List.copyOf(history);
    }
}
