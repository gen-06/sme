package com.creditscore.platform.ingestion;

import java.time.Instant;

/**
 * Marker contract for whatever shape a given adapter's raw sync output takes. Each
 * adapter defines its own record type implementing this (see
 * {@code mobilemoney.MobileMoneyRawTransaction}); the normalizer for that adapter type
 * knows how to map its fields onto {@code normalization.Transaction}. Kept minimal on
 * purpose — adding a field here would force every adapter to carry it even when it's
 * meaningless for that data source.
 */
public interface RawTransactionRecord {

    String externalReference();

    AdapterType sourceType();

    Instant occurredAt();
}
