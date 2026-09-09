package com.creditscore.platform.normalization;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    boolean existsByDataSourceIdAndExternalReference(UUID dataSourceId, String externalReference);

    List<Transaction> findByBusinessIdOrderByTransactionDateAsc(UUID businessId);

    Page<Transaction> findByBusinessId(UUID businessId, Pageable pageable);

    Page<Transaction> findByBusinessIdAndDirection(UUID businessId, Direction direction, Pageable pageable);
}
