package com.creditscore.platform.identity.auth.oauth2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;

/**
 * Holds exactly one row, at the fixed id {@link #PRIMARY_KEY_ID} — not an
 * AuditableEntity, since that base class's generated UUID id is wrong for a
 * singleton row. jwkJson stores the full RSA JWK (public AND private key material)
 * via nimbus's RSAKey.toJSONString().
 *
 * <p>Implements {@link Persistable} because this entity has a manually-assigned id,
 * not a database-generated one. Without this, Spring Data JPA's default isNew()
 * heuristic ("is the id field null?") is always false here — the id is set in the
 * constructor before the entity is ever saved — so save()/saveAndFlush() always
 * routes through entityManager.merge() (an upsert: SELECT, then INSERT or UPDATE)
 * instead of entityManager.persist() (a raw INSERT). merge() never throws on a
 * unique-constraint conflict — it silently UPDATEs the existing row instead — which
 * defeats OAuth2SigningKeyService's entire conflict-detection strategy: two instances
 * racing to create the first row would each silently overwrite the other's key with
 * no exception ever thrown, rather than one of them getting a real
 * DataIntegrityViolationException to recover from.
 */
@Entity
@Table(name = "oauth2_signing_keys")
public class OAuth2SigningKey implements Persistable<String> {

    public static final String PRIMARY_KEY_ID = "primary";

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "jwk_json", nullable = false, columnDefinition = "text")
    private String jwkJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean isNew = true;

    protected OAuth2SigningKey() {
        // JPA
    }

    public OAuth2SigningKey(String id, String jwkJson) {
        this.id = id;
        this.jwkJson = jwkJson;
        this.createdAt = Instant.now();
    }

    @Override
    public String getId() {
        return id;
    }

    public String getJwkJson() {
        return jwkJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        isNew = false;
    }
}
