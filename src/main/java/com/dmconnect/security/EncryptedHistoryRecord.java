package com.dmconnect.security;

import java.time.Instant;

public record EncryptedHistoryRecord(
        String id,
        String profileId,
        String profileName,
        Instant executedAt,
        boolean success,
        long durationMillis,
        EncryptedPayload sql) {
}
