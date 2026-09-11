package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuth2SigningKeyRepository extends JpaRepository<OAuth2SigningKey, String> {
}
