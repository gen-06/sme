package com.creditscore.platform.sync;

import com.creditscore.platform.ingestion.SyncStatus;

public record SyncOutcome(SyncStatus status, int transactionsInserted, int transactionsSkipped) {
}
