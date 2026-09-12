package com.creditscore.platform.api.dto;

import com.creditscore.platform.identity.consumer.ConsumerStatus;
import jakarta.validation.constraints.NotNull;

public record ConsumerStatusUpdateRequest(@NotNull ConsumerStatus status) {
}
