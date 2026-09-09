package com.creditscore.platform.billing;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.UuidGenerator;

import java.time.Instant;
import java.util.UUID;

/**
 * Raw usage/access-audit rows written on every authenticated API request. This is the
 * substance behind Consumer's "usage metering fields" and a basic access-audit trail
 * for the MVP; pricing/invoicing logic on top of these rows is deferred (see
 * {@link UsageMeter}).
 */
@Entity
@Table(name = "usage_records")
public class UsageRecord {

    @Id
    @UuidGenerator
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "consumer_id", nullable = false)
    private UUID consumerId;

    @Column(nullable = false)
    private String endpoint;

    @Column(nullable = false, length = 10)
    private String method;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    @Column(name = "response_status", nullable = false)
    private int responseStatus;

    protected UsageRecord() {
        // JPA
    }

    public UsageRecord(UUID consumerId, String endpoint, String method, int responseStatus) {
        this.consumerId = consumerId;
        this.endpoint = endpoint;
        this.method = method;
        this.calledAt = Instant.now();
        this.responseStatus = responseStatus;
    }

    public UUID getId() {
        return id;
    }

    public UUID getConsumerId() {
        return consumerId;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public String getMethod() {
        return method;
    }

    public Instant getCalledAt() {
        return calledAt;
    }

    public int getResponseStatus() {
        return responseStatus;
    }
}
