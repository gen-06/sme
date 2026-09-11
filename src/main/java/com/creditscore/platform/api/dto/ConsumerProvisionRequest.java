package com.creditscore.platform.api.dto;

import com.creditscore.platform.identity.consumer.ConsumerScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record ConsumerProvisionRequest(
        @NotBlank String name,
        String contactEmail,
        @NotEmpty Set<ConsumerScope> scopes) {
}
