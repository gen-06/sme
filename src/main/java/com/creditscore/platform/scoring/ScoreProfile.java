package com.creditscore.platform.scoring;

import com.creditscore.platform.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only: there is no update path for this entity anywhere in the codebase.
 * {@code GET /score} reads the latest row by {@code generatedAt}; {@code /score/history}
 * reads all rows. A row's {@code modelVersion} is fixed at insert time and never
 * migrated, which is what lets the active scoring engine change without altering
 * historical scores.
 */
@Entity
@Table(name = "score_profiles")
public class ScoreProfile extends AuditableEntity {

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal score;

    @Column(nullable = false, precision = 4, scale = 3)
    private BigDecimal confidence;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "factor_breakdown", nullable = false, columnDefinition = "jsonb")
    private List<ScoreFactor> factorBreakdown;

    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Enumerated(EnumType.STRING)
    @Column(name = "model_type", nullable = false, length = 20)
    private ModelType modelType;

    @Column(name = "window_start")
    private Instant windowStart;

    @Column(name = "window_end")
    private Instant windowEnd;

    @Column(name = "generated_at", nullable = false)
    private Instant generatedAt;

    protected ScoreProfile() {
        // JPA
    }

    public ScoreProfile(UUID businessId, BigDecimal score, BigDecimal confidence, List<ScoreFactor> factorBreakdown,
                         String modelVersion, ModelType modelType, Instant windowStart, Instant windowEnd) {
        this.businessId = businessId;
        this.score = score;
        this.confidence = confidence;
        this.factorBreakdown = factorBreakdown;
        this.modelVersion = modelVersion;
        this.modelType = modelType;
        this.windowStart = windowStart;
        this.windowEnd = windowEnd;
        this.generatedAt = Instant.now();
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public BigDecimal getScore() {
        return score;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public List<ScoreFactor> getFactorBreakdown() {
        return factorBreakdown;
    }

    public String getModelVersion() {
        return modelVersion;
    }

    public ModelType getModelType() {
        return modelType;
    }

    public Instant getWindowStart() {
        return windowStart;
    }

    public Instant getWindowEnd() {
        return windowEnd;
    }

    public Instant getGeneratedAt() {
        return generatedAt;
    }
}
