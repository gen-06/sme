package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.UsageRecordResponse;
import com.creditscore.platform.api.dto.UsageSummaryResponse;
import com.creditscore.platform.billing.UsageRecordRepository;
import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private static final int MAX_ENDPOINT_BUCKETS = 20;

    private static final Pattern UUID_SEGMENT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final UsageRecordRepository usageRecordRepository;

    public UsageController(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@AuthenticationPrincipal Consumer consumer) {
        var byEndpoint = usageRecordRepository.findEndpointsByConsumerId(consumer.getId()).stream()
                .map(this::normalizeEndpoint)
                .collect(Collectors.groupingBy(endpoint -> endpoint, Collectors.counting()))
                .entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(MAX_ENDPOINT_BUCKETS)
                .map(entry -> new UsageSummaryResponse.EndpointCount(entry.getKey(), entry.getValue()))
                .toList();
        var recentCalls = usageRecordRepository.findTop20ByConsumerIdOrderByCalledAtDesc(consumer.getId()).stream()
                .map(UsageRecordResponse::from)
                .toList();
        long totalCalls = usageRecordRepository.countByConsumerId(consumer.getId());

        return new UsageSummaryResponse(totalCalls, byEndpoint, recentCalls);
    }

    /**
     * Collapses UUID-shaped path segments (e.g. business IDs) into a {@code {id}}
     * placeholder so the usage breakdown groups by route/endpoint template rather
     * than by concrete request URI. Without this, {@code request.getRequestURI()}
     * (recorded per-call in {@code ApiKeyAuthFilter}) produces one bucket per
     * distinct resource ID ever hit, instead of one bucket per route shape.
     */
    private String normalizeEndpoint(String endpoint) {
        return UUID_SEGMENT.matcher(endpoint).replaceAll("{id}");
    }
}
