package com.creditscore.platform.normalization;

import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.SyncResult;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NormalizationService {

    private final NormalizerRegistry normalizerRegistry;
    private final TransactionRepository transactionRepository;

    public NormalizationService(NormalizerRegistry normalizerRegistry, TransactionRepository transactionRepository) {
        this.normalizerRegistry = normalizerRegistry;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public NormalizationOutcome normalizeAndPersist(DataSource dataSource, SyncResult syncResult) {
        TransactionNormalizer normalizer = normalizerRegistry.resolve(dataSource.getAdapterType());
        var candidates = normalizer.normalize(dataSource, syncResult.records());

        int inserted = 0;
        int skipped = 0;
        for (Transaction candidate : candidates) {
            if (transactionRepository.existsByDataSourceIdAndExternalReference(
                    candidate.getDataSourceId(), candidate.getExternalReference())) {
                skipped++;
                continue;
            }
            try {
                transactionRepository.save(candidate);
                inserted++;
            } catch (DataIntegrityViolationException e) {
                // Backstop against the DB unique constraint if a race slipped past the
                // existence check above; treat as an already-synced record.
                skipped++;
            }
        }
        return new NormalizationOutcome(inserted, skipped);
    }
}
