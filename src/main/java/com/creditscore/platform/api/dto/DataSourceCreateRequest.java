package com.creditscore.platform.api.dto;

import com.creditscore.platform.ingestion.AdapterType;
import jakarta.validation.constraints.NotNull;

public record DataSourceCreateRequest(
        @NotNull AdapterType adapterType,
        String provider
) {
}
