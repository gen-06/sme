package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ScoreFactor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

@Component
class ConsistencyRule implements ScoringRule {

    private static final BigDecimal GAP_MONTH_PENALTY = BigDecimal.valueOf(20);

    @Override
    public String name() {
        return "consistency";
    }

    @Override
    public ScoreFactor evaluate(Business business, List<Transaction> transactions) {
        Map<YearMonth, BigDecimal> monthly = MonthlyInflow.bucket(transactions);

        if (monthly.size() < 2) {
            return new ScoreFactor(name(), null, BigDecimal.ZERO, BigDecimal.valueOf(50), null,
                    "Insufficient data: fewer than two months of inflow activity observed.");
        }

        double mean = monthly.values().stream().mapToDouble(BigDecimal::doubleValue).average().orElse(0);
        long gapMonths = monthly.values().stream().filter(v -> v.compareTo(BigDecimal.ZERO) == 0).count();

        double normalized;
        String explanation;
        if (mean == 0) {
            normalized = 0;
            explanation = "No inflow activity across the observed window.";
        } else {
            double variance = monthly.values().stream()
                    .mapToDouble(v -> Math.pow(v.doubleValue() - mean, 2))
                    .average().orElse(0);
            double stdDev = Math.sqrt(variance);
            double coefficientOfVariation = stdDev / mean;

            normalized = Math.max(0, 100 * (1 - Math.min(coefficientOfVariation, 1)));
            explanation = "Coefficient of variation across " + monthly.size() + " months was "
                    + String.format("%.2f", coefficientOfVariation) + ".";
        }

        if (gapMonths > 0) {
            normalized = Math.max(0, normalized - GAP_MONTH_PENALTY.doubleValue() * gapMonths);
            explanation += " " + gapMonths + " month(s) with zero inflow activity detected.";
        }

        return new ScoreFactor(name(), null, BigDecimal.valueOf(gapMonths),
                BigDecimal.valueOf(normalized).setScale(2, RoundingMode.HALF_UP), null, explanation);
    }
}
