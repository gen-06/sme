package com.creditscore.platform.api.dto;

import java.util.List;

public record UsageSummaryResponse(
        long totalCalls,
        List<EndpointCount> byEndpoint,
        List<UsageRecordResponse> recentCalls
) {
    public record EndpointCount(String endpoint, long count) {
    }
}
