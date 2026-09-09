package com.creditscore.platform.scoring;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ScoreResult(
        BigDecimal score,
        BigDecimal confidence,
        List<ScoreFactor> factors,
        Instant windowStart,
        Instant windowEnd
) {
}
