package com.creditscore.platform.identity.business;

import com.creditscore.platform.common.AuditableEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import java.time.LocalDate;

@Entity
@Table(name = "businesses")
public class Business extends AuditableEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, length = 2)
    private String country;

    @Column
    private String industry;

    @Column(name = "registration_number")
    private String registrationNumber;

    @Column(name = "registration_date")
    private LocalDate registrationDate;

    @Column(name = "onboarding_date", nullable = false)
    private LocalDate onboardingDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BusinessStatus status;

    protected Business() {
        // JPA
    }

    public Business(String name, String country, String industry, String registrationNumber,
                     LocalDate registrationDate) {
        this.name = name;
        this.country = country;
        this.industry = industry;
        this.registrationNumber = registrationNumber;
        this.registrationDate = registrationDate;
        this.onboardingDate = LocalDate.now();
        this.status = BusinessStatus.ACTIVE;
    }

    public String getName() {
        return name;
    }

    public String getCountry() {
        return country;
    }

    public String getIndustry() {
        return industry;
    }

    public String getRegistrationNumber() {
        return registrationNumber;
    }

    public LocalDate getRegistrationDate() {
        return registrationDate;
    }

    public LocalDate getOnboardingDate() {
        return onboardingDate;
    }

    public BusinessStatus getStatus() {
        return status;
    }

    public void setStatus(BusinessStatus status) {
        this.status = status;
    }
}
