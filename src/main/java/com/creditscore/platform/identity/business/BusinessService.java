package com.creditscore.platform.identity.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class BusinessService {

    private final BusinessRepository businessRepository;

    public BusinessService(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    @Transactional
    public Business register(String name, String country, String industry, String registrationNumber,
                              LocalDate registrationDate) {
        Business business = new Business(name, country, industry, registrationNumber, registrationDate);
        return businessRepository.save(business);
    }

    public Business getOrThrow(UUID businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new NoSuchElementException("Business not found: " + businessId));
    }
}
