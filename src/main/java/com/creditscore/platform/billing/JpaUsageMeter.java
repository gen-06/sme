package com.creditscore.platform.billing;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
public class JpaUsageMeter implements UsageMeter {

    private final UsageRecordRepository usageRecordRepository;

    public JpaUsageMeter(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    @Override
    @Transactional
    public void record(UUID consumerId, String endpoint, String method, int responseStatus) {
        usageRecordRepository.save(new UsageRecord(consumerId, endpoint, method, responseStatus));
    }
}
