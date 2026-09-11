package com.creditscore.platform.identity.auth.oauth2;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.text.ParseException;
import java.util.UUID;

/**
 * createAndPersist() deliberately carries no @Transactional of its own: its only
 * database call, saveAndFlush(), is already independently transactional via Spring
 * Data's repository proxy. Giving createAndPersist() its own REQUIRED transaction
 * would make saveAndFlush() join it instead of running standalone — then a unique-
 * constraint conflict marks that shared transaction rollback-only, and returning
 * normally from createAndPersist() throws UnexpectedRollbackException on commit
 * regardless of this method's own catch block. Losing that transaction boundary is
 * what makes the conflict-recovery path actually reachable.
 *
 * recoverAfterConflict() runs in REQUIRES_NEW (a genuinely separate transaction,
 * invoked through the @Lazy self-proxy so the annotation isn't defeated by
 * self-invocation) for two reasons: it must not inherit any rollback-only state,
 * and it needs a fresh EntityManager — after a constraint-violation exception the
 * Hibernate session that threw it is left in an undefined state, so a recovery read
 * must not reuse it.
 *
 * Residual limitation, deliberately not solved: a future caller invoking
 * getOrCreateSigningKey() from within its own active transaction would still see
 * that caller's transaction marked rollback-only on a conflict (REQUIRES_NEW
 * protects the recovery read, not the caller's ambient transaction). Not addressed
 * here because the only real caller, SecurityConfig.jwkSource(), is a @Bean factory
 * method that never runs inside a transaction.
 */
@Service
public class OAuth2SigningKeyService {

    private static final Logger log = LoggerFactory.getLogger(OAuth2SigningKeyService.class);

    private final OAuth2SigningKeyRepository repository;
    private final OAuth2SigningKeyService self;

    public OAuth2SigningKeyService(OAuth2SigningKeyRepository repository, @Lazy OAuth2SigningKeyService self) {
        this.repository = repository;
        this.self = self;
    }

    public RSAKey getOrCreateSigningKey() {
        return repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)
                .map(this::parse)
                .orElseGet(this::createAndPersist);
    }

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
            log.info("Lost the race to create the OAuth2 signing key row; recovering the winning key", e);
            return self.recoverAfterConflict();
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public RSAKey recoverAfterConflict() {
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
