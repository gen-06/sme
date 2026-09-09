package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ModelType;
import com.creditscore.platform.scoring.ScoreFactor;
import com.creditscore.platform.scoring.ScoreResult;
import com.creditscore.platform.scoring.ScoringEngine;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;

@Component
public class RuleBasedScoringEngineV1 implements ScoringEngine {

    private static final String MODEL_VERSION = "rule-based-v1";
    private static final double TARGET_WINDOW_DAYS = 180;

    private final List<ScoringRule> rules;
    private final RuleWeightsProperties weights;

    public RuleBasedScoringEngineV1(List<ScoringRule> rules, RuleWeightsProperties weights) {
        this.rules = rules;
        this.weights = weights;
    }

    @Override
    public String getModelVersion() {
        return MODEL_VERSION;
    }

    @Override
    public ModelType getModelType() {
        return ModelType.RULE_BASED;
    }

    @Override
    public ScoreResult computeScore(Business business, List<Transaction> transactions) {
        List<ScoreFactor> factors = rules.stream()
                .map(rule -> applyWeight(rule.evaluate(business, transactions)))
                .toList();

        BigDecimal score = factors.stream()
                .map(ScoreFactor::contribution)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        Instant windowStart = transactions.stream().map(Transaction::getTransactionDate)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant windowEnd = transactions.stream().map(Transaction::getTransactionDate)
                .max(Comparator.naturalOrder()).orElse(null);

        BigDecimal confidence = computeConfidence(windowStart, windowEnd);

        return new ScoreResult(score, confidence, factors, windowStart, windowEnd);
    }

    private ScoreFactor applyWeight(ScoreFactor factor) {
        BigDecimal weight = weights.weightFor(factor.name());
        BigDecimal contribution = weight.multiply(factor.normalizedValue()).setScale(4, RoundingMode.HALF_UP);
        return new ScoreFactor(factor.name(), weight, factor.rawValue(), factor.normalizedValue(), contribution,
                factor.explanation());
    }

    private BigDecimal computeConfidence(Instant windowStart, Instant windowEnd) {
        if (windowStart == null || windowEnd == null) {
            return BigDecimal.ZERO;
        }
        long observedDays = ChronoUnit.DAYS.between(windowStart, windowEnd);
        double confidence = Math.min(1.0, observedDays / TARGET_WINDOW_DAYS);
        return BigDecimal.valueOf(confidence).setScale(3, RoundingMode.HALF_UP);
    }
}
