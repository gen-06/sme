package com.creditscore.platform.api.dto;

import com.creditscore.platform.ingestion.AdapterType;
import com.creditscore.platform.ingestion.ConnectionStatus;
import com.creditscore.platform.ingestion.DataSource;

import java.time.Instant;
import java.util.UUID;

public record DataSourceResponse(
        UUID id,
        UUID businessId,
        AdapterType adapterType,
        String provider,
        ConnectionStatus connectionStatus,
        String currency,
        Instant lastSyncedAt
) {
    public static DataSourceResponse from(DataSource dataSource) {
        return new DataSourceResponse(
                dataSource.getId(),
                dataSource.getBusinessId(),
                dataSource.getAdapterType(),
                dataSource.getProvider(),
                dataSource.getConnectionStatus(),
                dataSource.getCurrency(),
                dataSource.getLastSyncedAt());
    }
}
