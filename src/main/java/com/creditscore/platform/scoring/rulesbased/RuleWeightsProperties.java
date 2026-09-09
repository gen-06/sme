package com.creditscore.platform.scoring.rulesbased;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;

@ConfigurationProperties(prefix = "app.scoring.rule-based-v1.weights")
public record RuleWeightsProperties(
        BigDecimal transactionVolume,
        BigDecimal consistency,
        BigDecimal growthTrend,
        BigDecimal missedPaymentSignal
) {

    BigDecimal weightFor(String ruleName) {
        return switch (ruleName) {
            case "transaction_volume" -> transactionVolume;
            case "consistency" -> consistency;
            case "growth_trend" -> growthTrend;
            case "missed_payment_signal" -> missedPaymentSignal;
            default -> throw new IllegalArgumentException("Unknown rule: " + ruleName);
        };
    }
}
