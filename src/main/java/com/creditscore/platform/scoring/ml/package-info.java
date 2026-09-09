/**
 * Reserved for an ML-based {@code ScoringEngine} implementation. Adding
 * {@code MlScoringEngineV1 implements ScoringEngine} here and flipping
 * {@code app.scoring.active-version} is the entire cutover — every caller depends
 * only on the {@code ScoringEngine} interface, and historical {@code ScoreProfile}
 * rows are never touched by construction.
 */
package com.creditscore.platform.scoring.ml;
