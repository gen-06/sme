package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionStatus;
import com.creditscore.platform.scoring.ScoreFactor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Proxy signal only: real repayment/missed-payment data isn't available from any
 * connected source yet (that requires a lending-relationship data feed this platform
 * doesn't ingest today). This approximates it from the failure rate of the business's
 * own transactions as a stand-in signal, and says so in every factor it produces.
 */
@Component
class MissedPaymentSignalRule implements ScoringRule {

    @Override
    public String name() {
        return "missed_payment_signal";
    }

    @Override
    public ScoreFactor evaluate(Business business, List<Transaction> transactions) {
        if (transactions.isEmpty()) {
            return new ScoreFactor(name(), null, BigDecimal.ZERO, BigDecimal.valueOf(50), null,
                    "Insufficient data: no transactions observed. Proxy signal from transaction failure rate; "
                            + "not a direct measure of loan repayment history.");
        }

        long failedCount = transactions.stream().filter(tx -> tx.getStatus() == TransactionStatus.FAILED).count();
        double failureRate = (double) failedCount / transactions.size();

        double normalized = Math.max(0, 100 * (1 - failureRate * 5));

        String explanation = String.format(
                "%.1f%% of %d observed transactions failed. Proxy signal from transaction failure rate; "
                        + "not a direct measure of loan repayment history.",
                failureRate * 100, transactions.size());

        return new ScoreFactor(name(), null, BigDecimal.valueOf(failureRate).setScale(4, RoundingMode.HALF_UP),
                BigDecimal.valueOf(normalized).setScale(2, RoundingMode.HALF_UP), null, explanation);
    }
}
