package com.creditscore.platform.identity.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Page<Business> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
