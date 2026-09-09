package com.creditscore.platform.ingestion.mobilemoney;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.DataAdapter;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.RawTransactionRecord;
import com.creditscore.platform.ingestion.SyncContext;
import com.creditscore.platform.ingestion.SyncResult;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Realistic mock M-Pesa-style mobile money adapter. There is no real mobile-money
 * integration/partnership yet — this exists to prove the ingestion -&gt; normalization
 * -&gt; scoring pipeline end to end without blocking on one. Generation is a pure,
 * deterministic function of {@code (dataSource.getId(), dataSource.getCreatedAt())}:
 * the same data source always produces the same synthetic history. This is what makes
 * re-sync idempotent (the normalizer's unique-reference constraint has something real
 * to de-duplicate against) rather than appending random new data on every call.
 */
@Component
public class MockMobileMoneyAdapter implements DataAdapter {

    private final MockMobileMoneyProperties properties;

    public MockMobileMoneyAdapter(MockMobileMoneyProperties properties) {
        this.properties = properties;
    }

    @Override
    public AdapterType getType() {
        return AdapterType.MOBILE_MONEY;
    }

    @Override
    public SyncResult sync(DataSource dataSource, SyncContext context) {
        List<MobileMoneyRawTransaction> fullHistory = generateDeterministicHistory(dataSource);

        List<RawTransactionRecord> filtered = fullHistory.stream()
                .filter(tx -> context.since() == null || tx.occurredAt().isAfter(context.since()))
                .map(tx -> (RawTransactionRecord) tx)
                .limit(context.maxRecords())
                .toList();

        Instant syncedThrough = dataSource.getCreatedAt();
        return SyncResult.success(filtered, syncedThrough);
    }

    private List<MobileMoneyRawTransaction> generateDeterministicHistory(DataSource dataSource) {
        Random random = new Random(dataSource.getId().getMostSignificantBits());
        Instant anchor = dataSource.getCreatedAt();
        YearMonth anchorMonth = YearMonth.from(anchor.atZone(ZoneOffset.UTC));

        int windowMonths = properties.windowMonths();
        int gapMonthIndex = properties.guaranteedGapMonth() ? random.nextInt(windowMonths) : -1;

        String tillNumber = "17" + String.format("%05d", Math.abs(dataSource.getId().hashCode()) % 100_000);

        List<MobileMoneyRawTransaction> transactions = new ArrayList<>();
        int referenceCounter = 0;

        for (int monthIndex = 0; monthIndex < windowMonths; monthIndex++) {
            if (monthIndex == gapMonthIndex) {
                continue;
            }

            YearMonth month = anchorMonth.minusMonths((long) windowMonths - 1 - monthIndex);
            double growthMultiplier = Math.pow(1 + properties.growthBias(), monthIndex);

            double monthlyInflowTarget = lerp(properties.monthlyInflowMin(), properties.monthlyInflowMax(), random.nextDouble())
                    * growthMultiplier;
            int inflowCount = randomBetween(random, properties.monthlyTransactionCountMin(),
                    properties.monthlyTransactionCountMax());

            double[] inflowShares = randomShares(random, inflowCount);
            for (int i = 0; i < inflowCount; i++) {
                referenceCounter++;
                MobileMoneyTransactionType type = random.nextBoolean()
                        ? MobileMoneyTransactionType.STK_PUSH
                        : MobileMoneyTransactionType.C2B;
                BigDecimal amount = toMoney(monthlyInflowTarget * inflowShares[i]);
                Instant occurredAt = randomInstantWithinMonth(random, month, anchor);
                MobileMoneyTransactionStatus status = random.nextDouble() < properties.failureRate()
                        ? MobileMoneyTransactionStatus.FAILED
                        : MobileMoneyTransactionStatus.COMPLETED;

                transactions.add(new MobileMoneyRawTransaction(
                        reference(dataSource, referenceCounter),
                        type,
                        maskedPhoneNumber(random),
                        tillNumber,
                        amount,
                        occurredAt,
                        status));
            }

            int outflowCount = Math.max(1, inflowCount / 5);
            double avgInflow = monthlyInflowTarget / Math.max(1, inflowCount);
            for (int i = 0; i < outflowCount; i++) {
                referenceCounter++;
                BigDecimal amount = toMoney(avgInflow * (0.3 + random.nextDouble() * 0.7));
                Instant occurredAt = randomInstantWithinMonth(random, month, anchor);
                MobileMoneyTransactionStatus status = random.nextDouble() < properties.failureRate()
                        ? MobileMoneyTransactionStatus.FAILED
                        : MobileMoneyTransactionStatus.COMPLETED;

                transactions.add(new MobileMoneyRawTransaction(
                        reference(dataSource, referenceCounter),
                        MobileMoneyTransactionType.B2C,
                        maskedPhoneNumber(random),
                        tillNumber,
                        amount,
                        occurredAt,
                        status));
            }
        }

        transactions.sort((a, b) -> a.transactionDate().compareTo(b.transactionDate()));
        return transactions;
    }

    private String reference(DataSource dataSource, int counter) {
        return "MPESA-" + dataSource.getId().toString().substring(0, 8).toUpperCase() + "-" + counter;
    }

    private String maskedPhoneNumber(Random random) {
        int lastFour = 1000 + random.nextInt(9000);
        return "2547" + random.nextInt(10) + "***" + lastFour;
    }

    /**
     * Picks a uniformly random instant within {@code month}, bounded above by
     * {@code anchor} for the anchor's own (partial) month. Sampling directly within
     * {@code [monthStart, upperBound]}, rather than sampling the full month and
     * clamping after the fact, avoids pushing a transaction into the *previous*
     * calendar month when the anchor falls early in its month (e.g. at midnight on
     * the 1st) — a post-hoc clamp to "anchor minus 1 hour" would do exactly that.
     */
    private Instant randomInstantWithinMonth(Random random, YearMonth month, Instant anchor) {
        Instant monthStart = month.atDay(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant monthEnd = month.atEndOfMonth().atTime(23, 59, 59).atZone(ZoneOffset.UTC).toInstant();
        Instant upperBound = monthEnd.isAfter(anchor) ? anchor : monthEnd;

        long rangeSeconds = Math.max(0, ChronoUnit.SECONDS.between(monthStart, upperBound));
        long offsetSeconds = rangeSeconds == 0 ? 0 : (long) (random.nextDouble() * rangeSeconds);
        return monthStart.plusSeconds(offsetSeconds);
    }

    private double[] randomShares(Random random, int count) {
        double[] raw = new double[count];
        double sum = 0;
        for (int i = 0; i < count; i++) {
            raw[i] = 0.2 + random.nextDouble();
            sum += raw[i];
        }
        for (int i = 0; i < count; i++) {
            raw[i] = raw[i] / sum;
        }
        return raw;
    }

    private int randomBetween(Random random, int min, int max) {
        return min + random.nextInt(Math.max(1, max - min + 1));
    }

    private double lerp(double min, double max, double t) {
        return min + (max - min) * t;
    }

    private BigDecimal toMoney(double value) {
        return BigDecimal.valueOf(Math.max(value, 1.0)).setScale(2, RoundingMode.HALF_UP);
    }
}
