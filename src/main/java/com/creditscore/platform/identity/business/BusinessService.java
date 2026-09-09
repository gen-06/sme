package com.creditscore.platform.identity.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    public Page<Business> list(Pageable pageable) {
        return businessRepository.findAll(pageable);
    }

    public Page<Business> search(String query, Pageable pageable) {
        return businessRepository.findByNameContainingIgnoreCase(query, pageable);
    }
}
