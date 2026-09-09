package com.creditscore.platform.normalization.mobilemoney;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.RawTransactionRecord;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyRawTransaction;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyTransactionStatus;
import com.creditscore.platform.ingestion.mobilemoney.MobileMoneyTransactionType;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionStatus;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MobileMoneyTransactionNormalizerTest {

    private final MobileMoneyTransactionNormalizer normalizer = new MobileMoneyTransactionNormalizer();

    @Test
    void mapsInflowTypesToInflowDirection() {
        DataSource dataSource = dataSourceWith(UUID.randomUUID(), "KES");
        MobileMoneyRawTransaction raw = new MobileMoneyRawTransaction(
                "MPESA-1", MobileMoneyTransactionType.STK_PUSH, "254712***678", "174001",
                BigDecimal.valueOf(1000), Instant.parse("2026-01-15T10:00:00Z"), MobileMoneyTransactionStatus.COMPLETED);

        List<Transaction> result = normalizer.normalize(dataSource, List.of(raw));

        assertThat(result).hasSize(1);
        Transaction transaction = result.get(0);
        assertThat(transaction.getDirection()).isEqualTo(Direction.INFLOW);
        assertThat(transaction.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
        assertThat(transaction.getCurrency()).isEqualTo("KES");
        assertThat(transaction.getExternalReference()).isEqualTo("MPESA-1");
        assertThat(transaction.getSourceType()).isEqualTo(AdapterType.MOBILE_MONEY);
    }

    @Test
    void mapsB2cToOutflowDirection() {
        DataSource dataSource = dataSourceWith(UUID.randomUUID(), "KES");
        MobileMoneyRawTransaction raw = new MobileMoneyRawTransaction(
                "MPESA-2", MobileMoneyTransactionType.B2C, "254712***678", "174001",
                BigDecimal.valueOf(500), Instant.parse("2026-01-16T10:00:00Z"), MobileMoneyTransactionStatus.FAILED);

        List<Transaction> result = normalizer.normalize(dataSource, List.of((RawTransactionRecord) raw));

        assertThat(result.get(0).getDirection()).isEqualTo(Direction.OUTFLOW);
        assertThat(result.get(0).getStatus()).isEqualTo(TransactionStatus.FAILED);
    }

    private DataSource dataSourceWith(UUID businessId, String currency) {
        DataSource dataSource = new DataSource(businessId, AdapterType.MOBILE_MONEY, "M-PESA", currency);
        ReflectionTestUtils.setField(dataSource, "id", UUID.randomUUID());
        return dataSource;
    }
}
