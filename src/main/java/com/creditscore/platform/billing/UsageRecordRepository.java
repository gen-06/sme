package com.creditscore.platform.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {
}
