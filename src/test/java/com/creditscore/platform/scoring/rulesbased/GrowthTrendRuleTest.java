package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionStatus;
import com.creditscore.platform.scoring.ScoreFactor;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class GrowthTrendRuleTest {

    private final GrowthTrendRule rule = new GrowthTrendRule();

    @Test
    void growingInflowScoresAboveNeutral() {
        List<Transaction> transactions = List.of(
                inflow(BigDecimal.valueOf(50_000), 0),
                inflow(BigDecimal.valueOf(50_000), 30),
                inflow(BigDecimal.valueOf(150_000), 60),
                inflow(BigDecimal.valueOf(150_000), 90));

        ScoreFactor factor = rule.evaluate(null, transactions);

        assertThat(factor.normalizedValue()).isGreaterThan(BigDecimal.valueOf(50));
    }

    @Test
    void shrinkingInflowScoresBelowNeutral() {
        List<Transaction> transactions = List.of(
                inflow(BigDecimal.valueOf(150_000), 0),
                inflow(BigDecimal.valueOf(150_000), 30),
                inflow(BigDecimal.valueOf(50_000), 60),
                inflow(BigDecimal.valueOf(50_000), 90));

        ScoreFactor factor = rule.evaluate(null, transactions);

        assertThat(factor.normalizedValue()).isLessThan(BigDecimal.valueOf(50));
    }

    @Test
    void insufficientDataReturnsNeutralScore() {
        ScoreFactor factor = rule.evaluate(null, List.of(inflow(BigDecimal.valueOf(1000), 0)));
        assertThat(factor.normalizedValue()).isEqualByComparingTo(BigDecimal.valueOf(50));
    }

    private Transaction inflow(BigDecimal amount, int daysOffset) {
        return new Transaction(
                UUID.randomUUID(), UUID.randomUUID(), "ref-" + daysOffset, amount, "KES",
                Instant.parse("2026-01-15T00:00:00Z").plus(daysOffset, ChronoUnit.DAYS), Direction.INFLOW, null,
                AdapterType.MOBILE_MONEY, TransactionStatus.COMPLETED);
    }
}
