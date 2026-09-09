package com.creditscore.platform.identity.consumer;

/**
 * Access scopes a Consumer (lender/fintech API client) can hold. Mirrors the MVP's
 * REST surface one-to-one; a real permissions model can layer on top later without
 * changing how scopes are stored (a real join table, not a jsonb blob, so Spring
 * Security can query them directly as GrantedAuthority values).
 */
public enum ConsumerScope {
    BUSINESS_WRITE,
    DATA_SOURCE_WRITE,
    SYNC_TRIGGER,
    SCORE_READ,
    TRANSACTION_READ
}
