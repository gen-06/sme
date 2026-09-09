package com.creditscore.platform.api.dto;

import com.creditscore.platform.scoring.ModelType;
import com.creditscore.platform.scoring.ScoreProfile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ScoreResponse(
        UUID businessId,
        BigDecimal score,
        BigDecimal confidence,
        ConfidenceLevel confidenceLevel,
        List<ScoreFactorResponse> factors,
        String modelVersion,
        ModelType modelType,
        Instant windowStart,
        Instant windowEnd,
        Instant generatedAt
) {
    public static ScoreResponse from(ScoreProfile profile) {
        return new ScoreResponse(
                profile.getBusinessId(),
                profile.getScore(),
                profile.getConfidence(),
                ConfidenceLevel.from(profile.getConfidence()),
                profile.getFactorBreakdown().stream().map(ScoreFactorResponse::from).toList(),
                profile.getModelVersion(),
                profile.getModelType(),
                profile.getWindowStart(),
                profile.getWindowEnd(),
                profile.getGeneratedAt());
    }
}
