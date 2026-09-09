package com.creditscore.platform.ingestion.mobilemoney;

import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.SyncContext;
import com.creditscore.platform.ingestion.SyncResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MockMobileMoneyAdapterTest {

    private final MockMobileMoneyProperties properties =
            new MockMobileMoneyProperties(6, 8, 40, 50_000, 400_000, 0.03, 0.06, true);
    private final MockMobileMoneyAdapter adapter = new MockMobileMoneyAdapter(properties);

    @Test
    void syncIsDeterministicForTheSameDataSource() {
        DataSource dataSource = dataSourceWith(UUID.randomUUID(), Instant.parse("2026-06-01T00:00:00Z"));

        SyncResult first = adapter.sync(dataSource, SyncContext.fullHistory());
        SyncResult second = adapter.sync(dataSource, SyncContext.fullHistory());

        assertThat(first.records()).isNotEmpty();
        assertThat(first.records()).isEqualTo(second.records());
    }

    @Test
    void exactlyOneMonthInTheWindowHasNoActivity() {
        Instant anchor = Instant.parse("2026-06-01T00:00:00Z");
        DataSource dataSource = dataSourceWith(UUID.randomUUID(), anchor);

        SyncResult result = adapter.sync(dataSource, SyncContext.fullHistory());

        Set<YearMonth> monthsWithActivity = new HashSet<>();
        for (var record : result.records()) {
            monthsWithActivity.add(YearMonth.from(record.occurredAt().atZone(ZoneOffset.UTC)));
        }

        // windowMonths = 6, guaranteedGapMonth = true -> exactly 5 active months.
        assertThat(monthsWithActivity).hasSize(properties.windowMonths() - 1);
    }

    @Test
    void sinceFilterExcludesRecordsBeforeTheGivenInstant() {
        Instant anchor = Instant.parse("2026-06-01T00:00:00Z");
        DataSource dataSource = dataSourceWith(UUID.randomUUID(), anchor);

        SyncResult fullHistory = adapter.sync(dataSource, SyncContext.fullHistory());
        Instant midpoint = anchor.minusSeconds(60L * 60 * 24 * 90);
        SyncResult incremental = adapter.sync(dataSource, SyncContext.incrementalFrom(midpoint));

        assertThat(incremental.records().size()).isLessThan(fullHistory.records().size());
        assertThat(incremental.records()).allSatisfy(r -> assertThat(r.occurredAt()).isAfter(midpoint));
    }

    private DataSource dataSourceWith(UUID id, Instant createdAt) {
        DataSource dataSource = new DataSource(UUID.randomUUID(), com.creditscore.platform.ingestion.AdapterType.MOBILE_MONEY,
                "M-PESA", "KES");
        ReflectionTestUtils.setField(dataSource, "id", id);
        ReflectionTestUtils.setField(dataSource, "createdAt", createdAt);
        return dataSource;
    }
}
