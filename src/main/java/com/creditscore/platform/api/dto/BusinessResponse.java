package com.creditscore.platform.api.dto;

import com.creditscore.platform.identity.business.Business;
import com.creditscore.platform.identity.business.BusinessStatus;

import java.time.LocalDate;
import java.util.UUID;

public record BusinessResponse(
        UUID id,
        String name,
        String country,
        String industry,
        String registrationNumber,
        LocalDate registrationDate,
        LocalDate onboardingDate,
        BusinessStatus status
) {
    public static BusinessResponse from(Business business) {
        return new BusinessResponse(
                business.getId(),
                business.getName(),
                business.getCountry(),
                business.getIndustry(),
                business.getRegistrationNumber(),
                business.getRegistrationDate(),
                business.getOnboardingDate(),
                business.getStatus());
    }
}
