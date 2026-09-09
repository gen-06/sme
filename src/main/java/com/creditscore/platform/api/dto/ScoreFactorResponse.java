package com.creditscore.platform.api.dto;

import com.creditscore.platform.scoring.ScoreFactor;

import java.math.BigDecimal;

public record ScoreFactorResponse(
        String name,
        BigDecimal weight,
        BigDecimal rawValue,
        BigDecimal normalizedValue,
        BigDecimal contribution,
        String explanation
) {
    public static ScoreFactorResponse from(ScoreFactor factor) {
        return new ScoreFactorResponse(factor.name(), factor.weight(), factor.rawValue(), factor.normalizedValue(),
                factor.contribution(), factor.explanation());
    }
}
