package com.creditscore.platform.identity.consumer;

import com.creditscore.platform.common.AuditableEntity;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "consumers")
public class Consumer extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_email")
    private String contactEmail;

    @Column(name = "api_key_hash", nullable = false, unique = true)
    private String apiKeyHash;

    @Column(name = "api_key_prefix", nullable = false, length = 16)
    private String apiKeyPrefix;

    @Column(name = "oauth_client_id")
    private String oauthClientId;

    @Column(name = "oauth_client_secret_hash")
    private String oauthClientSecretHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsumerStatus status;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "consumer_scopes", joinColumns = @JoinColumn(name = "consumer_id"))
    @Column(name = "scope", nullable = false)
    @Enumerated(EnumType.STRING)
    private Set<ConsumerScope> scopes = new HashSet<>();

    protected Consumer() {
        // JPA
    }

    public Consumer(String name, String contactEmail, String apiKeyHash, String apiKeyPrefix,
                     Set<ConsumerScope> scopes) {
        this.name = name;
        this.contactEmail = contactEmail;
        this.apiKeyHash = apiKeyHash;
        this.apiKeyPrefix = apiKeyPrefix;
        this.status = ConsumerStatus.ACTIVE;
        this.scopes = new HashSet<>(scopes);
    }

    public String getName() {
        return name;
    }

    public String getContactEmail() {
        return contactEmail;
    }

    public String getApiKeyHash() {
        return apiKeyHash;
    }

    public String getApiKeyPrefix() {
        return apiKeyPrefix;
    }

    public ConsumerStatus getStatus() {
        return status;
    }

    public Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public void setLastUsedAt(Instant lastUsedAt) {
        this.lastUsedAt = lastUsedAt;
    }

    public Set<ConsumerScope> getScopes() {
        return scopes;
    }

    public UUID getConsumerId() {
        return getId();
    }

    public static Consumer forOAuth2Client(String name, String contactEmail, Set<ConsumerScope> scopes) {
        Consumer consumer = new Consumer();
        consumer.name = name;
        consumer.contactEmail = contactEmail;
        consumer.status = ConsumerStatus.ACTIVE;
        consumer.scopes = new HashSet<>(scopes);
        return consumer;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(String oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public String getOauthClientSecretHash() {
        return oauthClientSecretHash;
    }

    public void setOauthClientSecretHash(String oauthClientSecretHash) {
        this.oauthClientSecretHash = oauthClientSecretHash;
    }
}
