package com.creditscore.platform.identity.consumer;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface ConsumerRepository extends JpaRepository<Consumer, UUID> {

    Optional<Consumer> findByApiKeyHash(String apiKeyHash);

    Optional<Consumer> findByOauthClientId(String oauthClientId);
}
