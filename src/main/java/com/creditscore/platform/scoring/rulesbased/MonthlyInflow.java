package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.normalization.Direction;
import com.creditscore.platform.normalization.Transaction;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Buckets completed inflow transactions by calendar month, filling in zero-value
 * entries for any month within the observed range that has no inflow at all — the
 * zero entries are what let {@code ConsistencyRule} detect activity gaps rather than
 * silently skipping months with no rows.
 */
final class MonthlyInflow {

    private MonthlyInflow() {
    }

    static Map<YearMonth, BigDecimal> bucket(List<Transaction> transactions) {
        Map<YearMonth, BigDecimal> byMonth = transactions.stream()
                .filter(tx -> tx.getDirection() == Direction.INFLOW)
                .collect(Collectors.groupingBy(
                        tx -> YearMonth.from(tx.getTransactionDate().atZone(ZoneOffset.UTC)),
                        TreeMap::new,
                        Collectors.reducing(BigDecimal.ZERO, Transaction::getAmount, BigDecimal::add)));

        if (byMonth.isEmpty()) {
            return byMonth;
        }

        YearMonth first = byMonth.keySet().stream().min(Comparator.naturalOrder()).orElseThrow();
        YearMonth last = byMonth.keySet().stream().max(Comparator.naturalOrder()).orElseThrow();
        Map<YearMonth, BigDecimal> filled = new TreeMap<>();
        for (YearMonth month = first; !month.isAfter(last); month = month.plusMonths(1)) {
            filled.put(month, byMonth.getOrDefault(month, BigDecimal.ZERO));
        }
        return filled;
    }
}
