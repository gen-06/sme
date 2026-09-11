package com.creditscore.platform.identity.auth.oauth2;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.util.UUID;

/**
 * Get-or-create for the platform's single OAuth2 signing key. Multiple instances can
 * race to create the first row at once — this is only a real scenario now that the
 * key is shared, not generated per-instance. Handled with an insert attempt followed
 * by a re-read on conflict, and the re-read runs in ITS OWN transaction
 * (REQUIRES_NEW): catching a constraint violation inside the transaction that threw it
 * leaves that transaction marked rollback-only in Spring/Hibernate, so any further
 * work in the SAME transaction — including the recovery read — would fail.
 */
@Service
public class OAuth2SigningKeyService {

    private final OAuth2SigningKeyRepository repository;

    public OAuth2SigningKeyService(OAuth2SigningKeyRepository repository) {
        this.repository = repository;
    }

    public RSAKey getOrCreateSigningKey() {
        return repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)
                .map(this::parse)
                .orElseGet(this::createAndPersist);
    }

    @Transactional
    RSAKey createAndPersist() {
        RSAKey generated;
        try {
            generated = new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
        } catch (JOSEException e) {
            throw new IllegalStateException("Failed to generate an RSA signing key", e);
        }
        try {
            repository.saveAndFlush(new OAuth2SigningKey(OAuth2SigningKey.PRIMARY_KEY_ID, generated.toJSONString()));
            return generated;
        } catch (DataIntegrityViolationException e) {
            return recoverAfterConflict();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    RSAKey recoverAfterConflict() {
        return repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)
                .map(this::parse)
                .orElseThrow(() -> new IllegalStateException(
                        "Signing key row vanished after a concurrent insert conflict"));
    }

    private RSAKey parse(OAuth2SigningKey entity) {
        try {
            return RSAKey.parse(entity.getJwkJson());
        } catch (ParseException e) {
            throw new IllegalStateException("Stored signing key JWK is not valid JSON", e);
        }
    }
}
