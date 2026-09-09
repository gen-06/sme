package com.creditscore.platform.api.dto;

import com.creditscore.platform.ingestion.SyncStatus;
import com.creditscore.platform.sync.SyncOutcome;

public record SyncResponse(
        SyncStatus status,
        int transactionsInserted,
        int transactionsSkipped
) {
    public static SyncResponse from(SyncOutcome outcome) {
        return new SyncResponse(outcome.status(), outcome.transactionsInserted(), outcome.transactionsSkipped());
    }
}
