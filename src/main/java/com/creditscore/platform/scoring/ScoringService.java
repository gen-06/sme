package com.creditscore.platform.scoring;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.identity.business.BusinessService;
import com.creditscore.platform.normalization.Transaction;
import com.creditscore.platform.normalization.TransactionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class ScoringService {

    private final BusinessService businessService;
    private final TransactionRepository transactionRepository;
    private final ScoringEngineRegistry scoringEngineRegistry;
    private final ScoreProfileRepository scoreProfileRepository;
    private final String activeVersion;

    public ScoringService(BusinessService businessService, TransactionRepository transactionRepository,
                           ScoringEngineRegistry scoringEngineRegistry, ScoreProfileRepository scoreProfileRepository,
                           @Value("${app.scoring.active-version}") String activeVersion) {
        this.businessService = businessService;
        this.transactionRepository = transactionRepository;
        this.scoringEngineRegistry = scoringEngineRegistry;
        this.scoreProfileRepository = scoreProfileRepository;
        this.activeVersion = activeVersion;
    }

    @Transactional
    public ScoreProfile computeAndPersistScore(UUID businessId) {
        Business business = businessService.getOrThrow(businessId);
        List<Transaction> transactions = transactionRepository.findByBusinessIdOrderByTransactionDateAsc(businessId);

        ScoringEngine engine = scoringEngineRegistry.resolve(activeVersion);
        ScoreResult result = engine.computeScore(business, transactions);

        ScoreProfile profile = new ScoreProfile(
                businessId,
                result.score(),
                result.confidence(),
                result.factors(),
                engine.getModelVersion(),
                engine.getModelType(),
                result.windowStart(),
                result.windowEnd());

        return scoreProfileRepository.save(profile);
    }

    public ScoreProfile getLatestOrThrow(UUID businessId) {
        return scoreProfileRepository.findFirstByBusinessIdOrderByGeneratedAtDesc(businessId)
                .orElseThrow(() -> new java.util.NoSuchElementException(
                        "No score computed yet for business: " + businessId));
    }

    public List<ScoreProfile> getHistory(UUID businessId) {
        return scoreProfileRepository.findByBusinessIdOrderByGeneratedAtDesc(businessId);
    }
}
