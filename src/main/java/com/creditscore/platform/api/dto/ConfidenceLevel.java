package com.creditscore.platform.api.dto;

import java.math.BigDecimal;

public enum ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH;

    public static ConfidenceLevel from(BigDecimal confidence) {
        double value = confidence.doubleValue();
        if (value >= 0.7) {
            return HIGH;
        }
        if (value >= 0.35) {
            return MEDIUM;
        }
        return LOW;
    }
}
