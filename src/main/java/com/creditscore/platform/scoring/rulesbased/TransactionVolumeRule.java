package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ScoreFactor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

@Component
class TransactionVolumeRule implements ScoringRule {

    // Trailing-window total inflow that maps to a normalized score of 100. Chosen to
    // sit comfortably above the mock adapter's max window total (400k/month x 6mo),
    // so a real, healthy business still has headroom rather than pinning at the cap.
    private static final BigDecimal VOLUME_TARGET = BigDecimal.valueOf(1_800_000);

    @Override
    public String name() {
        return "transaction_volume";
    }

    @Override
    public ScoreFactor evaluate(Business business, List<Transaction> transactions) {
        BigDecimal totalInflow = transactions.stream()
                .filter(tx -> tx.getDirection() == Direction.INFLOW)
                .map(Transaction::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal normalized = totalInflow.equals(BigDecimal.ZERO)
                ? BigDecimal.ZERO
                : totalInflow.divide(VOLUME_TARGET, 6, RoundingMode.HALF_UP)
                        .multiply(BigDecimal.valueOf(100))
                        .min(BigDecimal.valueOf(100));

        String explanation = totalInflow.equals(BigDecimal.ZERO)
                ? "No inflow transactions observed."
                : "Total inflow over the observed window was " + totalInflow.setScale(2, RoundingMode.HALF_UP) + ".";

        return new ScoreFactor(name(), null, totalInflow, normalized.setScale(2, RoundingMode.HALF_UP), null, explanation);
    }
}
