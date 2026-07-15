package com.dmconnect.model;

import java.time.Instant;

public record HistoryEntry(
        String id,
        String profileId,
        String profileName,
        Instant executedAt,
        boolean success,
        long durationMillis,
        String sql) {
}
