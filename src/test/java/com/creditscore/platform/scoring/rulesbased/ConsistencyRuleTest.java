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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ConsistencyRuleTest {

    private final ConsistencyRule rule = new ConsistencyRule();

    @Test
    void steadyMonthlyInflowScoresHigh() {
        List<Transaction> transactions = monthlyInflows(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000),
                BigDecimal.valueOf(100_000));

        ScoreFactor factor = rule.evaluate(null, transactions);

        assertThat(factor.normalizedValue()).isGreaterThan(BigDecimal.valueOf(90));
    }

    @Test
    void volatileMonthlyInflowScoresLowerThanSteady() {
        List<Transaction> steady = monthlyInflows(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000));
        List<Transaction> volatile_ = monthlyInflows(
                BigDecimal.valueOf(10_000), BigDecimal.valueOf(200_000), BigDecimal.valueOf(20_000));

        ScoreFactor steadyFactor = rule.evaluate(null, steady);
        ScoreFactor volatileFactor = rule.evaluate(null, volatile_);

        assertThat(volatileFactor.normalizedValue()).isLessThan(steadyFactor.normalizedValue());
    }

    @Test
    void gapMonthReducesScore() {
        List<Transaction> withoutGap = monthlyInflows(
                BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000), BigDecimal.valueOf(100_000));
        List<Transaction> withGap = monthlyInflows(
                BigDecimal.valueOf(100_000), BigDecimal.ZERO, BigDecimal.valueOf(100_000));

        ScoreFactor withoutGapFactor = rule.evaluate(null, withoutGap);
        ScoreFactor withGapFactor = rule.evaluate(null, withGap);

        assertThat(withGapFactor.normalizedValue()).isLessThan(withoutGapFactor.normalizedValue());
    }

    private List<Transaction> monthlyInflows(BigDecimal... monthlyAmounts) {
        List<Transaction> transactions = new ArrayList<>();
        Instant month0 = Instant.parse("2026-01-15T00:00:00Z");
        for (int i = 0; i < monthlyAmounts.length; i++) {
            if (monthlyAmounts[i].compareTo(BigDecimal.ZERO) == 0) {
                continue;
            }
            transactions.add(new Transaction(
                    UUID.randomUUID(), UUID.randomUUID(), "ref-" + i, monthlyAmounts[i], "KES",
                    month0.plus(30L * i, ChronoUnit.DAYS), Direction.INFLOW, null,
                    AdapterType.MOBILE_MONEY, TransactionStatus.COMPLETED));
        }
        return transactions;
    }
}
