package com.creditscore.platform.billing;

/**
 * Spring Data JPA interface-based projection for the grouped-by-endpoint query in
 * {@link UsageRecordRepository#countByEndpointForConsumer}. Getter names must match
 * the query's result aliases (endpoint, callCount).
 */
public interface EndpointUsageCount {

    String getEndpoint();

    long getCallCount();
}
