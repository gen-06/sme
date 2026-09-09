package com.creditscore.platform.ingestion.mobilemoney;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.RawTransactionRecord;

import java.math.BigDecimal;
import java.time.Instant;

public record MobileMoneyRawTransaction(
        String mpesaReceiptNumber,
        MobileMoneyTransactionType transactionType,
        String phoneNumberMasked,
        String tillOrPaybillNumber,
        BigDecimal amount,
        Instant transactionDate,
        MobileMoneyTransactionStatus transactionStatus
) implements RawTransactionRecord {

    @Override
    public String externalReference() {
        return mpesaReceiptNumber;
    }

    @Override
    public AdapterType sourceType() {
        return AdapterType.MOBILE_MONEY;
    }

    @Override
    public Instant occurredAt() {
        return transactionDate;
    }
}
