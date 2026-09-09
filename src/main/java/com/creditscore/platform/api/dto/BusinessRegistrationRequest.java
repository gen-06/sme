package com.creditscore.platform.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

import java.time.LocalDate;

public record BusinessRegistrationRequest(
        @NotBlank String name,
        @NotBlank @Pattern(regexp = "^[A-Z]{2}$", message = "must be an ISO 3166-1 alpha-2 country code") String country,
        String industry,
        String registrationNumber,
        LocalDate registrationDate
) {
}
