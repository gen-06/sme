package com.creditscore.platform.scoring;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ScoreProfileRepository extends JpaRepository<ScoreProfile, UUID> {

    Optional<ScoreProfile> findFirstByBusinessIdOrderByGeneratedAtDesc(UUID businessId);

    List<ScoreProfile> findByBusinessIdOrderByGeneratedAtDesc(UUID businessId);
}
