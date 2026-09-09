package com.creditscore.platform.scoring.rulesbased;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.scoring.ScoreFactor;

import java.util.List;

/**
 * One independently-testable signal feeding {@link RuleBasedScoringEngineV1}.
 * Implementations return a raw/normalized value and explanation; weighting and
 * aggregation into a final score happen centrally in the engine.
 */
public interface ScoringRule {

    String name();

    ScoreFactor evaluate(Business business, List<Transaction> transactions);
}
