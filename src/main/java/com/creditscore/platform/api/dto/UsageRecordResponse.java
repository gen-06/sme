package com.creditscore.platform.api.dto;

import com.creditscore.platform.billing.UsageRecord;

import java.time.Instant;

public record UsageRecordResponse(
        String endpoint,
        String method,
        Instant calledAt,
        int responseStatus
) {
    public static UsageRecordResponse from(UsageRecord record) {
        return new UsageRecordResponse(record.getEndpoint(), record.getMethod(), record.getCalledAt(),
                record.getResponseStatus());
    }
}
