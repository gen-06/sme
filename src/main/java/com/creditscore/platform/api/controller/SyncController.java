package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.SyncResponse;
import com.creditscore.platform.identity.business.BusinessService;
import com.creditscore.platform.ingestion.DataSource;
import com.creditscore.platform.ingestion.DataSourceService;
import com.creditscore.platform.ingestion.SyncStatus;
import com.creditscore.platform.scoring.ScoringService;
import com.creditscore.platform.sync.DataSyncService;
import com.creditscore.platform.sync.SyncOutcome;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/businesses/{businessId}/sync")
public class SyncController {

    private final BusinessService businessService;
    private final DataSourceService dataSourceService;
    private final DataSyncService dataSyncService;
    private final ScoringService scoringService;

    public SyncController(BusinessService businessService, DataSourceService dataSourceService,
                           DataSyncService dataSyncService, ScoringService scoringService) {
        this.businessService = businessService;
        this.dataSourceService = dataSourceService;
        this.dataSyncService = dataSyncService;
        this.scoringService = scoringService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SYNC_TRIGGER')")
    public ResponseEntity<SyncResponse> sync(@PathVariable UUID businessId) {
        businessService.getOrThrow(businessId);
        List<DataSource> dataSources = dataSourceService.listForBusiness(businessId);

        int inserted = 0;
        int skipped = 0;
        SyncStatus aggregateStatus = SyncStatus.SUCCESS;

        for (DataSource dataSource : dataSources) {
            SyncOutcome outcome = dataSyncService.sync(dataSource);
            inserted += outcome.transactionsInserted();
            skipped += outcome.transactionsSkipped();
            if (outcome.status() != SyncStatus.SUCCESS) {
                aggregateStatus = SyncStatus.PARTIAL;
            }
        }

        if (!dataSources.isEmpty()) {
            scoringService.computeAndPersistScore(businessId);
        }

        return ResponseEntity.ok(new SyncResponse(aggregateStatus, inserted, skipped));
    }
}
