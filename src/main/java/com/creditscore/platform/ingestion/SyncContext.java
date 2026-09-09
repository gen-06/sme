package com.creditscore.platform.ingestion;

import java.time.Instant;

/**
 * @param since        only fetch/generate records at or after this instant; null means
 *                      "full history" (first sync for this data source).
 * @param maxRecords    a soft cap an adapter may respect to bound sync duration/cost.
 */
public record SyncContext(Instant since, int maxRecords) {

    public static SyncContext incrementalFrom(Instant since) {
        return new SyncContext(since, 5_000);
    }

    public static SyncContext fullHistory() {
        return new SyncContext(null, 5_000);
    }
}
