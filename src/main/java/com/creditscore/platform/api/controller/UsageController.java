package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.UsageRecordResponse;
import com.creditscore.platform.api.dto.UsageSummaryResponse;
import com.creditscore.platform.billing.UsageRecordRepository;
import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageRecordRepository usageRecordRepository;

    public UsageController(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@AuthenticationPrincipal Consumer consumer) {
        var byEndpoint = usageRecordRepository.countByEndpointForConsumer(consumer.getId()).stream()
                .map(row -> new UsageSummaryResponse.EndpointCount(row.getEndpoint(), row.getCallCount()))
                .toList();
        var recentCalls = usageRecordRepository.findTop20ByConsumerIdOrderByCalledAtDesc(consumer.getId()).stream()
                .map(UsageRecordResponse::from)
                .toList();
        long totalCalls = usageRecordRepository.countByConsumerId(consumer.getId());

        return new UsageSummaryResponse(totalCalls, byEndpoint, recentCalls);
    }
}
