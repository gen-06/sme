package com.creditscore.platform.scoring;

import java.math.BigDecimal;

public record ScoreFactor(
        String name,
        BigDecimal weight,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal contribution,
        String explanation
) {
}
