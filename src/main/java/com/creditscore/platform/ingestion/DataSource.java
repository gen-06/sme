package com.creditscore.platform.ingestion;

import com.creditscore.platform.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "data_sources", uniqueConstraints = @UniqueConstraint(columnNames = {"id", "business_id"}))
public class DataSource extends AuditableEntity {

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Enumerated(EnumType.STRING)
    @Column(name = "adapter_type", nullable = false, length = 20)
    private AdapterType adapterType;

    @Column
    private String provider;

    @Enumerated(EnumType.STRING)
    @Column(name = "connection_status", nullable = false, length = 20)
    private ConnectionStatus connectionStatus;

    @Column(nullable = false, length = 3)
    private String currency;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "connection_config", columnDefinition = "jsonb")
    private String connectionConfig;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    protected DataSource() {
        // JPA
    }

    public DataSource(UUID businessId, AdapterType adapterType, String provider, String currency) {
        this.businessId = businessId;
        this.adapterType = adapterType;
        this.provider = provider;
        this.currency = currency;
        this.connectionStatus = ConnectionStatus.CONNECTED;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public AdapterType getAdapterType() {
        return adapterType;
    }

    public String getProvider() {
        return provider;
    }

    public ConnectionStatus getConnectionStatus() {
        return connectionStatus;
    }

    public void setConnectionStatus(ConnectionStatus connectionStatus) {
        this.connectionStatus = connectionStatus;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getLastSyncedAt() {
        return lastSyncedAt;
    }

    public void setLastSyncedAt(Instant lastSyncedAt) {
        this.lastSyncedAt = lastSyncedAt;
    }
}
