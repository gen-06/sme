package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ScoreFactor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
class GrowthTrendRule implements ScoringRule {

    @Override
    public String name() {
        return "growth_trend";
    }

    @Override
    public ScoreFactor evaluate(Business business, List<Transaction> transactions) {
        Map<YearMonth, BigDecimal> monthly = MonthlyInflow.bucket(transactions);
        List<BigDecimal> values = new ArrayList<>(monthly.values());

        if (values.size() < 2) {
            return new ScoreFactor(name(), null, BigDecimal.ZERO, BigDecimal.valueOf(50), null,
                    "Insufficient data: fewer than two months of inflow activity observed.");
        }

        int midpoint = values.size() / 2;
        BigDecimal firstHalfTotal = sum(values.subList(0, midpoint));
        BigDecimal secondHalfTotal = sum(values.subList(midpoint, values.size()));

        double growthPercent;
        if (firstHalfTotal.compareTo(BigDecimal.ZERO) == 0) {
            growthPercent = secondHalfTotal.compareTo(BigDecimal.ZERO) > 0 ? 100 : 0;
        } else {
            growthPercent = secondHalfTotal.subtract(firstHalfTotal)
                    .divide(firstHalfTotal, 6, RoundingMode.HALF_UP)
                    .doubleValue() * 100;
        }

        // Neutral at 0% growth, saturating at +/-25% growth.
        double normalized = clamp(50 + growthPercent * 2, 0, 100);

        String explanation = String.format(
                "Second-half inflow was %.1f%% relative to the first half of the observed window.",
                growthPercent);

        return new ScoreFactor(name(), null, BigDecimal.valueOf(growthPercent).setScale(2, RoundingMode.HALF_UP),
                BigDecimal.valueOf(normalized).setScale(2, RoundingMode.HALF_UP), null, explanation);
    }

    private BigDecimal sum(List<BigDecimal> values) {
        return values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
