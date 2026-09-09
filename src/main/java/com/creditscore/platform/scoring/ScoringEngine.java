package com.creditscore.platform.scoring;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.normalization.Transaction;

import java.util.List;

/**
 * The creditworthiness engine's contract. Depends only on
 * {@code normalization.Transaction} — never on {@code ingestion} or {@code api} — so
 * scoring stays market-agnostic and swappable independent of where the data came from.
 * Every caller (services, controllers) depends only on this interface, which is what
 * makes swapping the rule-based v1 implementation for an ML model later a config
 * change ({@code app.scoring.active-version}), not a rewrite.
 */
public interface ScoringEngine {

    String getModelVersion();

    ModelType getModelType();

    ScoreResult computeScore(Business business, List<Transaction> transactions);
}
