package com.creditscore.platform.normalization;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.RawTransactionRecord;

import java.util.List;

/**
 * Maps one adapter's raw output onto the standard {@link Transaction} model. Depends
 * only on {@code ingestion}'s contract types (one-directional) — {@code scoring} never
 * sees {@link RawTransactionRecord} at all, only the {@link Transaction}s this produces.
 */
public interface TransactionNormalizer {

    AdapterType supports();

    List<Transaction> normalize(DataSource dataSource, List<RawTransactionRecord> rawRecords);
}
