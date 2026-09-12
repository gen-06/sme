package com.creditscore.platform.api.dto;

import com.creditscore.platform.identity.consumer.ConsumerStatus;

import java.util.UUID;

public record ConsumerStatusResponse(UUID consumerId, ConsumerStatus status) {
}
