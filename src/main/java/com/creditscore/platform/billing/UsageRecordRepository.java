package com.creditscore.platform.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    long countByConsumerId(UUID consumerId);

    List<UsageRecord> findTop20ByConsumerIdOrderByCalledAtDesc(UUID consumerId);

    @Query("select u.endpoint from UsageRecord u where u.consumerId = :consumerId")
    List<String> findEndpointsByConsumerId(@Param("consumerId") UUID consumerId);
}
