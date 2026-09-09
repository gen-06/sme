package com.creditscore.platform.normalization;

import com.creditscore.platform.common.AuditableEntity;
import com.creditscore.platform.ingestion.AdapterType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions", uniqueConstraints = @UniqueConstraint(columnNames = {"data_source_id", "external_reference"}))
public class Transaction extends AuditableEntity {

    @Column(name = "data_source_id", nullable = false)
    private UUID dataSourceId;

    @Column(name = "business_id", nullable = false)
    private UUID businessId;

    @Column(name = "external_reference", nullable = false)
    private String externalReference;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(name = "transaction_date", nullable = false)
    private Instant transactionDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Direction direction;

    @Column
    private String counterparty;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 20)
    private AdapterType sourceType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private TransactionStatus status;

    protected Transaction() {
        // JPA
    }

    public Transaction(UUID dataSourceId, UUID businessId, String externalReference, BigDecimal amount,
                        String currency, Instant transactionDate, Direction direction, String counterparty,
                        AdapterType sourceType, TransactionStatus status) {
        this.dataSourceId = dataSourceId;
        this.businessId = businessId;
        this.externalReference = externalReference;
        this.amount = amount;
        this.currency = currency;
        this.transactionDate = transactionDate;
        this.direction = direction;
        this.counterparty = counterparty;
        this.sourceType = sourceType;
        this.status = status;
    }

    public UUID getDataSourceId() {
        return dataSourceId;
    }

    public UUID getBusinessId() {
        return businessId;
    }

    public String getExternalReference() {
        return externalReference;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getTransactionDate() {
        return transactionDate;
    }

    public Direction getDirection() {
        return direction;
    }

    public String getCounterparty() {
        return counterparty;
    }

    public AdapterType getSourceType() {
        return sourceType;
    }

    public TransactionStatus getStatus() {
        return status;
    }
}
