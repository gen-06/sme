package com.creditscore.platform.api.dto;

import java.util.UUID;

public record ConsumerProvisionResponse(UUID consumerId, String clientId, String clientSecret) {
}
