package com.creditscore.platform.sync;

import com.creditscore.platform.ingestion.DataAdapter;
import com.creditscore.platform.ingestion.DataAdapterRegistry;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.DataSourceRepository;
import com.creditscore.platform.ingestion.SyncContext;
import com.creditscore.platform.ingestion.SyncResult;
import com.creditscore.platform.normalization.NormalizationOutcome;
import com.creditscore.platform.normalization.NormalizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place in the codebase allowed to know about both {@code ingestion} and
 * {@code normalization} — it orchestrates adapter sync followed by normalization and
 * persistence. Both the REST sync endpoint and the scheduled Batch reconciliation job
 * call this rather than talking to adapters/normalizers directly.
 */
@Service
public class DataSyncService {

    private final DataAdapterRegistry adapterRegistry;
    private final NormalizationService normalizationService;
    private final DataSourceRepository dataSourceRepository;

    public DataSyncService(DataAdapterRegistry adapterRegistry, NormalizationService normalizationService,
                            DataSourceRepository dataSourceRepository) {
        this.adapterRegistry = adapterRegistry;
        this.normalizationService = normalizationService;
        this.dataSourceRepository = dataSourceRepository;
    }

    @Transactional
    public SyncOutcome sync(DataSource dataSource) {
        DataAdapter adapter = adapterRegistry.resolve(dataSource.getAdapterType());
        SyncContext context = dataSource.getLastSyncedAt() == null
                ? SyncContext.fullHistory()
                : SyncContext.incrementalFrom(dataSource.getLastSyncedAt());

        SyncResult syncResult = adapter.sync(dataSource, context);
        NormalizationOutcome normalizationOutcome = normalizationService.normalizeAndPersist(dataSource, syncResult);

        dataSource.setLastSyncedAt(syncResult.syncedThrough());
        dataSourceRepository.save(dataSource);

        return new SyncOutcome(syncResult.status(), normalizationOutcome.inserted(), normalizationOutcome.skipped());
    }
}
