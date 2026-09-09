package com.creditscore.platform.api.dto;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID dataSourceId,
        String externalReference,
        BigDecimal amount,
        String currency,
        Instant transactionDate,
        Direction direction,
        String counterparty,
        AdapterType sourceType,
        TransactionStatus status
) {
    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getDataSourceId(),
                transaction.getExternalReference(),
                transaction.getAmount(),
                transaction.getCurrency(),
                transaction.getTransactionDate(),
                transaction.getDirection(),
                transaction.getCounterparty(),
                transaction.getSourceType(),
                transaction.getStatus());
    }
}
