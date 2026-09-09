package com.creditscore.platform.ingestion;

import java.time.Instant;
import java.util.List;

public record SyncResult(List<RawTransactionRecord> records, Instant syncedThrough, SyncStatus status,
                          String message) {

    public static SyncResult success(List<RawTransactionRecord> records, Instant syncedThrough) {
        return new SyncResult(records, syncedThrough, SyncStatus.SUCCESS, null);
    }
}
