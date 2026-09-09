package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionStatus;
import com.creditscore.platform.scoring.ScoreFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MissedPaymentSignalRuleTest {

    private final MissedPaymentSignalRule rule = new MissedPaymentSignalRule();

    @Test
    void noTransactionsReturnsNeutralInsufficientDataScore() {
        ScoreFactor factor = rule.evaluate(null, List.of());
        assertThat(factor.normalizedValue()).isEqualByComparingTo(BigDecimal.valueOf(50));
        assertThat(factor.explanation()).contains("Insufficient data");
    }

    @Test
    void allCompletedTransactionsScoresMax() {
        List<Transaction> transactions = transactions(10, 0);
        ScoreFactor factor = rule.evaluate(null, transactions);
        assertThat(factor.normalizedValue()).isEqualByComparingTo(BigDecimal.valueOf(100));
    }

    @Test
    void higherFailureRateScoresLower() {
        ScoreFactor lowFailure = rule.evaluate(null, transactions(19, 1));
        ScoreFactor highFailure = rule.evaluate(null, transactions(10, 10));

        assertThat(highFailure.normalizedValue()).isLessThan(lowFailure.normalizedValue());
    }

    private List<Transaction> transactions(int completedCount, int failedCount) {
        List<Transaction> transactions = new ArrayList<>();
        for (int i = 0; i < completedCount; i++) {
            transactions.add(tx("c-" + i, TransactionStatus.COMPLETED));
        }
        for (int i = 0; i < failedCount; i++) {
            transactions.add(tx("f-" + i, TransactionStatus.FAILED));
        }
        return transactions;
    }

    private Transaction tx(String ref, TransactionStatus status) {
        return new Transaction(
                UUID.randomUUID(), UUID.randomUUID(), ref, BigDecimal.valueOf(1000), "KES",
                Instant.parse("2026-01-01T00:00:00Z"), Direction.INFLOW, null, AdapterType.MOBILE_MONEY, status);
    }
}
