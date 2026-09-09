package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ScoreFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TransactionVolumeRuleTest {

    private final TransactionVolumeRule rule = new TransactionVolumeRule();

    @Test
    void noTransactionsYieldsZeroScore() {
        ScoreFactor factor = rule.evaluate(null, List.of());
        assertThat(factor.normalizedValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void higherInflowYieldsHigherNormalizedScore() {
        ScoreFactor low = rule.evaluate(null, inflowTransactions(BigDecimal.valueOf(10_000)));
        ScoreFactor high = rule.evaluate(null, inflowTransactions(BigDecimal.valueOf(1_000_000)));

        assertThat(high.normalizedValue()).isGreaterThan(low.normalizedValue());
        assertThat(high.normalizedValue()).isLessThanOrEqualTo(BigDecimal.valueOf(100));
    }

    @Test
    void outflowTransactionsDoNotContributeToVolume() {
        Transaction outflow = new Transaction(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "ref-1", BigDecimal.valueOf(50_000),
                "KES", Instant.parse("2026-01-01T00:00:00Z"), Direction.OUTFLOW, null,
                com.creditscore.platform.ingestion.AdapterType.MOBILE_MONEY,
                com.creditscore.platform.normalization.TransactionStatus.COMPLETED);

        ScoreFactor factor = rule.evaluate(null, List.of(outflow));
        assertThat(factor.rawValue()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    private List<Transaction> inflowTransactions(BigDecimal amount) {
        return List.of(new Transaction(
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), "ref-1", amount,
                "KES", Instant.parse("2026-01-01T00:00:00Z"), Direction.INFLOW, null,
                com.creditscore.platform.ingestion.AdapterType.MOBILE_MONEY,
                com.creditscore.platform.normalization.TransactionStatus.COMPLETED));
    }
}
