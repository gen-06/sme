package com.creditscore.platform.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    long countByConsumerId(UUID consumerId);

    List<UsageRecord> findTop20ByConsumerIdOrderByCalledAtDesc(UUID consumerId);

    @Query("select u.endpoint as endpoint, count(u) as callCount from UsageRecord u "
            + "where u.consumerId = :consumerId group by u.endpoint order by count(u) desc")
    List<EndpointUsageCount> countByEndpointForConsumer(@Param("consumerId") UUID consumerId);
}
