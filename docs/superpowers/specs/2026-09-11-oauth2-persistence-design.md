# OAuth2 Signing Key + Authorization Store Persistence — Design Spec

## Context

The OAuth2 client-credentials feature (`docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md`)
shipped with two explicitly documented MVP limitations that together cap this platform
at a single instance:

- **`SecurityConfig.jwkSource()`** generates a fresh RSA keypair in memory on every
  application startup. A restart invalidates every outstanding access token
  immediately, and two instances would sign with different keys and reject each
  other's tokens.
- **Spring Authorization Server's default `OAuth2AuthorizationService`** (auto-selected
  when no bean is registered) is `InMemoryOAuth2AuthorizationService` — it retains one
  entry per issued token with no eviction, so heap grows until the process restarts,
  and each instance's in-memory store is invisible to every other instance.

This spec covers persisting both to the platform's existing Postgres database — no new
infrastructure — and adding the cleanup mechanism the persisted authorization store
needs (it inherits no built-in eviction from moving off-heap).

**Explicitly not covered by this pass:** token revocation before natural expiry. The
resource-server side (`OAuth2ConsumerAuthenticationConverter`) validates bearer tokens
purely by JWT signature and expiry — it has never consulted the authorization store on
the request path, and this work doesn't change that. Persisting the store makes
revocation *buildable* later (the data becomes durable and queryable), but building it
is a separate future pass.

## Decisions made before design (confirmed with the user)

- **Signing key storage: plaintext in Postgres**, not encrypted-at-rest and not an
  external secret store (Vault/KMS). This matches every other piece of sensitive data
  in this platform (hashed API keys, hashed OAuth2 client secrets, business data) —
  all live in the same Postgres instance under the same trust boundary. An encrypted
  variant would introduce a second "root secret" (the encryption key) that has to live
  somewhere — the same class of problem this session's final review flagged for
  `PLATFORM_ADMIN_TOKEN`, just moved one level up, not eliminated. An external KMS is
  the more "correct" production answer but would be new infrastructure introduced
  solely for one key, with no other pattern in this codebase to extend.
- **Authorization store cleanup: a scheduled deletion job**, not "persist and leave
  unbounded." Moving the store from heap to Postgres changes the failure mode from
  "the JVM eventually OOMs" to "a table grows forever" — safer, but still genuinely
  unbounded unless something deletes expired rows. A `@Scheduled` job closes the
  finding rather than relocating it.

## Architecture

### Signing key persistence

New JPA entity `identity.auth.oauth2.OAuth2SigningKey`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

@Entity
@Table(name = "oauth2_signing_keys")
public class OAuth2SigningKey {

    public static final String PRIMARY_KEY_ID = "primary";

    @Id
    @Column(length = 50)
    private String id;

    @Column(name = "jwk_json", nullable = false, columnDefinition = "text")
    private String jwkJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected OAuth2SigningKey() {
        // JPA
    }

    public OAuth2SigningKey(String id, String jwkJson) {
        this.id = id;
        this.jwkJson = jwkJson;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getJwkJson() {
        return jwkJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
```

Not `extends AuditableEntity` — that base class's `@UuidGenerator` produces a random
id per row, which is wrong for a table meant to hold exactly one row at a
well-known, fixed key (`"primary"`). `jwk_json` stores the full RSA JWK — public
**and** private key material — via nimbus's `RSAKey.toJSONString()` (which includes
private parameters by default when called on a key built with a private exponent, as
`RSAKeyGenerator` produces).

A small repository:

```java
package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuth2SigningKeyRepository extends JpaRepository<OAuth2SigningKey, String> {
}
```

And a service that get-or-creates the key, handling the first-boot race between
multiple instances explicitly:

```java
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
```

`SecurityConfig.jwkSource()` changes from generating a key inline to asking this
service:

```java
@Bean
public JWKSource<SecurityContext> jwkSource(OAuth2SigningKeyService signingKeyService) {
    JWKSet jwkSet = new JWKSet(signingKeyService.getOrCreateSigningKey());
    return new ImmutableJWKSet<>(jwkSet);
}
```

(Removes the `JOSEException`-throwing inline `RSAKeyGenerator` call and the
`java.util.UUID` import that's no longer used directly in `SecurityConfig`.)

### Authorization store persistence

New Flyway migration creates `oauth2_authorization`, adapted from Spring Authorization
Server's own reference schema (`org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql`
inside `spring-security-oauth2-authorization-server-1.3.3.jar` — extracted and
Postgres-adapted per the schema file's own header comment: *"If using PostgreSQL,
update ALL columns defined with 'blob' to 'text'"*). See Schema changes below for the
literal migration.

A new bean in `SecurityConfig`:

```java
@Bean
public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                         RegisteredClientRepository registeredClientRepository) {
    JdbcOAuth2AuthorizationService service =
            new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);

    ObjectMapper objectMapper = new ObjectMapper();
    objectMapper.registerModules(SecurityJackson2Modules.getModules(JdbcOAuth2AuthorizationService.class.getClassLoader()));
    objectMapper.registerModule(new OAuth2AuthorizationServerJackson2Module());

    JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper rowMapper =
            new JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper(registeredClientRepository);
    rowMapper.setObjectMapper(objectMapper);
    service.setAuthorizationRowMapper(rowMapper);

    JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper parametersMapper =
            new JdbcOAuth2AuthorizationService.OAuth2AuthorizationParametersMapper();
    parametersMapper.setObjectMapper(objectMapper);
    service.setAuthorizationParametersMapper(parametersMapper);

    return service;
}
```

`JdbcTemplate` needs no new Maven dependency — confirmed via `mvnw dependency:tree`
that `spring-boot-starter-jdbc`/`spring-jdbc` are already transitively present (pulled
in by `spring-boot-starter-data-jpa`), and Spring Boot's `JdbcTemplateAutoConfiguration`
activates automatically once a `DataSource` bean exists, which it already does. The
`ObjectMapper` wiring (both the row mapper's and the parameters mapper's
`setObjectMapper`) is Spring Authorization Server's own documented requirement for
JDBC-backed storage — without the Security + Authorization-Server Jackson modules
registered, the JSON stored in `attributes`/`metadata` columns won't round-trip.
`JpaRegisteredClientRepository` (already built, already the app's only
`RegisteredClientRepository` bean) needs no changes — it satisfies the constructor
Spring Authorization Server's JDBC classes expect as-is.

Spring Authorization Server auto-detects this bean the same way it already
auto-detects `JpaRegisteredClientRepository` and `OAuth2ConsumerTokenCustomizer` —
`OAuth2AuthorizationServerConfigurer` looks up an `OAuth2AuthorizationService` bean if
one exists, falling back to the in-memory default only when none is registered. No
change to `authorizationServerFilterChain` itself is needed.

### Cleanup job

This platform only issues `client_credentials` tokens — no authorization code, device
code, or refresh token ever gets written (those columns stay `NULL` on every row), so
every row that exists has `access_token_expires_at` populated the moment it's created.
A single bulk delete on that column is a complete cleanup predicate; no Spring Batch
job is warranted for one SQL statement.

```java
package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

@Component
public class OAuth2AuthorizationCleanupJob {

    private final JdbcTemplate jdbcTemplate;

    public OAuth2AuthorizationCleanupJob(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Scheduled(cron = "${app.oauth2.authorization-cleanup-cron}")
    public void deleteExpiredAuthorizations() {
        jdbcTemplate.update("DELETE FROM oauth2_authorization WHERE access_token_expires_at < ?",
                Timestamp.from(Instant.now()));
    }
}
```

New `application.yml` property, alongside the existing `app.oauth2.access-token-ttl-minutes`:

```yaml
  oauth2:
    access-token-ttl-minutes: 60
    authorization-cleanup-cron: "0 30 * * * *"
```

(Every hour at :30, offset from `app.batch.sync-cron`'s every-6-hours-on-the-hour so
the two scheduled jobs don't contend for the same moment; both use the existing
`@EnableScheduling` already active on `PlatformApplication`.)

## Schema changes

Two new migrations, `V10` and `V11` (next in the existing `V1`–`V9` sequence):

**`V10__oauth2_authorization_store.sql`** — Spring Authorization Server's reference
schema, `blob` → `text` per the schema file's own instruction:

```sql
CREATE TABLE oauth2_authorization (
    id                            VARCHAR(100) NOT NULL PRIMARY KEY,
    registered_client_id          VARCHAR(100) NOT NULL,
    principal_name                VARCHAR(200) NOT NULL,
    authorization_grant_type      VARCHAR(100) NOT NULL,
    authorized_scopes             VARCHAR(1000) DEFAULT NULL,
    attributes                    TEXT DEFAULT NULL,
    state                         VARCHAR(500) DEFAULT NULL,
    authorization_code_value      TEXT DEFAULT NULL,
    authorization_code_issued_at  TIMESTAMPTZ DEFAULT NULL,
    authorization_code_expires_at TIMESTAMPTZ DEFAULT NULL,
    authorization_code_metadata   TEXT DEFAULT NULL,
    access_token_value            TEXT DEFAULT NULL,
    access_token_issued_at        TIMESTAMPTZ DEFAULT NULL,
    access_token_expires_at       TIMESTAMPTZ DEFAULT NULL,
    access_token_metadata         TEXT DEFAULT NULL,
    access_token_type             VARCHAR(100) DEFAULT NULL,
    access_token_scopes           VARCHAR(1000) DEFAULT NULL,
    oidc_id_token_value           TEXT DEFAULT NULL,
    oidc_id_token_issued_at       TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_expires_at      TIMESTAMPTZ DEFAULT NULL,
    oidc_id_token_metadata        TEXT DEFAULT NULL,
    refresh_token_value           TEXT DEFAULT NULL,
    refresh_token_issued_at       TIMESTAMPTZ DEFAULT NULL,
    refresh_token_expires_at      TIMESTAMPTZ DEFAULT NULL,
    refresh_token_metadata        TEXT DEFAULT NULL,
    user_code_value               TEXT DEFAULT NULL,
    user_code_issued_at           TIMESTAMPTZ DEFAULT NULL,
    user_code_expires_at          TIMESTAMPTZ DEFAULT NULL,
    user_code_metadata            TEXT DEFAULT NULL,
    device_code_value             TEXT DEFAULT NULL,
    device_code_issued_at         TIMESTAMPTZ DEFAULT NULL,
    device_code_expires_at        TIMESTAMPTZ DEFAULT NULL,
    device_code_metadata          TEXT DEFAULT NULL
);

CREATE INDEX idx_oauth2_authorization_access_token_expires_at
    ON oauth2_authorization (access_token_expires_at);
```

The index is not part of Spring Authorization Server's reference schema — added
because `OAuth2AuthorizationCleanupJob` runs a `WHERE access_token_expires_at < ?`
delete every hour against a table that, in this app's actual usage, is scanned by that
predicate far more often than by anything else.

**`V11__oauth2_signing_keys.sql`**:

```sql
CREATE TABLE oauth2_signing_keys (
    id         VARCHAR(50) PRIMARY KEY,
    jwk_json   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
```

## Verification

- Pure unit tests where genuinely unit-testable without a Spring context (matching
  this codebase's no-`@SpringBootTest` convention): `OAuth2SigningKeyService`'s
  get-or-create logic against a mocked `OAuth2SigningKeyRepository` (existing row
  found → parsed and returned; no row → generated, saved, returned; save throws
  `DataIntegrityViolationException` → recovery read returns the winning row).
- Manual, live verification against a real Postgres (this app's established pattern
  for anything JDBC/Spring-Security-integration-heavy, same as every other task in
  the original OAuth2 plan): boot the app once, confirm a `oauth2_signing_keys` row
  and a `oauth2_authorization` row exist after a token is issued; restart the app,
  confirm the SAME key (same `kid` at `/oauth2/jwks`) loads rather than a new one
  being generated; boot two instances against the same fresh (empty-table) database
  as close to simultaneously as practical to exercise the insert-conflict path, and
  confirm both end up serving the same key rather than one instance failing outright;
  confirm a token minted by one instance validates successfully against the other;
  manually insert an expired `oauth2_authorization` row (or wait out a short TTL) and
  confirm the cleanup job removes it on its next scheduled run.

## Known limitations (unchanged or newly relevant)

- **Still no token revocation before expiry.** Persisting the authorization store
  makes this buildable later — the data is now durable and queryable — but nothing in
  this pass adds a revocation check to the resource-server request path.
- **Still no client-secret rotation flow** (unchanged from the original OAuth2 spec).
- **No signing-key rotation.** This pass persists one key indefinitely; rotating to a
  new key while keeping the old public key available to validate not-yet-expired
  tokens (a multi-key `JWKSet` keyed by `kid`) is a real future increment, deliberately
  out of scope here — mirrors the already-accepted "no client-secret rotation" gap
  rather than introducing a new, larger PKI-management scope in the same pass.
- **The private key sits in Postgres in plaintext.** Accepted per the storage-approach
  decision above; anyone with the database has the signing key, same trust boundary as
  every other credential this platform stores.

## Out of scope for this pass

Token revocation, signing-key rotation, encrypting the stored key, moving to an
external secret store/KMS, and any change to `RegisteredClient`/`Consumer` persistence
(unaffected — `JpaRegisteredClientRepository` is reused as-is).
