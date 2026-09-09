package com.creditscore.platform.billing;

import java.util.UUID;

/**
 * Records that a consumer made a request. This pass only logs raw usage rows —
 * pricing/invoicing logic (usage-based billing, per the product brief's "path from
 * MVP to production") is deferred and would be built on top of {@link UsageRecord}
 * rows without changing this interface.
 */
public interface UsageMeter {

    void record(UUID consumerId, String endpoint, String method, int responseStatus);
}
