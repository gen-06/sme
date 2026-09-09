package com.creditscore.platform.ingestion.mobilemoney;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.ingestion.mock-mobile-money")
public record MockMobileMoneyProperties(
        int windowMonths,
        int monthlyTransactionCountMin,
        int monthlyTransactionCountMax,
        double monthlyInflowMin,
        double monthlyInflowMax,
        double growthBias,
        double failureRate,
        boolean guaranteedGapMonth
) {
}
