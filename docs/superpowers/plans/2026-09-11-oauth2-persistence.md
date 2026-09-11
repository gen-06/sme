# OAuth2 Signing Key + Authorization Store Persistence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist the OAuth2 signing key and the OAuth2 authorization store to Postgres (currently both in-memory), so the OAuth2 auth layer can run as more than one instance and stops growing without bound — closing the two blockers a prior review left explicitly documented.

**Architecture:** A new JPA entity/repository/service persists a single RSA signing key, get-or-created idempotently at startup with explicit handling for two instances racing to create it for the first time. A new `OAuth2AuthorizationService` bean (`JdbcOAuth2AuthorizationService`, Spring Authorization Server's own JDBC implementation) replaces the auto-selected in-memory default, backed by a new Postgres table adapted from Spring Authorization Server's reference schema. A new scheduled job deletes expired rows from that table, since moving off-heap doesn't add eviction on its own.

**Tech Stack:** No new Maven dependencies — `spring-jdbc`/`JdbcTemplate` is already transitively present (confirmed via `./mvnw dependency:tree`) and auto-configured once a `DataSource` bean exists, which it already does.

**Spec:** `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md`

## Global Constraints

- No `@SpringBootTest`/`@DataJpaTest` anywhere — every new test is a pure JUnit 5 + Mockito + AssertJ unit test with no Spring context, matching this repo's established convention. Logic that can only really be verified against a live Postgres (bean wiring, the real DB-level unique-constraint race, Jackson round-tripping) is verified manually, not forced into a unit test.
- `OAuth2SigningKey` does NOT extend `AuditableEntity` — that base class's `@UuidGenerator` produces a random id per row, wrong for a table meant to hold exactly one row at a fixed, well-known id (`"primary"`).
- The conflict-recovery read after a `DataIntegrityViolationException` MUST run in its own transaction (`Propagation.REQUIRES_NEW`). Catching that exception inside the same transaction that threw it leaves the transaction marked rollback-only in Spring/Hibernate — any further work in that same transaction, including a recovery read, would fail. This is a correctness-critical detail, not a style preference.
- Both `JdbcOAuth2AuthorizationService.OAuth2AuthorizationRowMapper` and `OAuth2AuthorizationParametersMapper` need `setObjectMapper(...)` called with `SecurityJackson2Modules.getModules(...)` plus `OAuth2AuthorizationServerJackson2Module` registered on the same `ObjectMapper` instance. Omitting either one breaks JSON round-tripping of the stored authorization's `attributes`/`metadata` columns — this is Spring Authorization Server's own documented JDBC setup requirement, confirmed present in the resolved `spring-security-oauth2-authorization-server:1.3.3` jar.
- Spring Authorization Server auto-detects an `OAuth2AuthorizationService` bean from the Spring context the same way it already auto-detects `JpaRegisteredClientRepository` and `OAuth2ConsumerTokenCustomizer` — confirmed by reading `OAuth2ConfigurerUtils.getAuthorizationService` in the actual 1.3.3 source: it checks a shared object, falls back to a bean-type lookup, and only falls back further to `new InMemoryOAuth2AuthorizationService()` if no bean exists. No explicit `.authorizationService(...)` call is needed anywhere in `SecurityConfig`.
- This platform only ever issues `client_credentials` tokens — the `authorization_code_*`, `refresh_token_*`, `user_code_*`, and `device_code_*` columns in `oauth2_authorization` are always `NULL`. `access_token_expires_at` alone is therefore a complete cleanup predicate; no per-token-type branching is needed.
- Migrations continue sequentially: `V10__oauth2_signing_keys.sql`, then `V11__oauth2_authorization_store.sql` (next after the existing `V1`–`V9`).
- `RSAKey.toJSONString()` includes the private key material by default when called on a key with a private exponent (as `RSAKeyGenerator` produces) — empirically confirmed (generated a key, serialized it, confirmed the JSON contains a `"d"` field and that `RSAKey.parse(json).isPrivate()` is `true` and `toRSAPrivateKey()` is non-null). `RSAKey.parse(String)` is a static method declared directly on `RSAKey` (not just inherited from `JWK`), so it returns `RSAKey` with no cast needed.

---

### Task 1: Signing key persistence

**Files:**
- Create: `src/main/resources/db/migration/V10__oauth2_signing_keys.sql`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKey.java`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyRepository.java`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyService.java`
- Test: `src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyServiceTest.java`
- Modify: `src/main/java/com/creditscore/platform/config/SecurityConfig.java`

**Interfaces:**
- Produces: `OAuth2SigningKeyService.getOrCreateSigningKey() -> RSAKey` — `SecurityConfig.jwkSource()` calls this instead of generating a key inline. `OAuth2SigningKey.PRIMARY_KEY_ID` (the constant `"primary"`) is the fixed row id every later reference to this table uses.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V10__oauth2_signing_keys.sql`:

```sql
CREATE TABLE oauth2_signing_keys (
    id         VARCHAR(50) PRIMARY KEY,
    jwk_json   TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
```

- [ ] **Step 2: Write the entity**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKey.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Holds exactly one row, at the fixed id {@link #PRIMARY_KEY_ID} — not an
 * AuditableEntity, since that base class's generated UUID id is wrong for a
 * singleton row. jwkJson stores the full RSA JWK (public AND private key material)
 * via nimbus's RSAKey.toJSONString().
 */
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

- [ ] **Step 3: Write the repository**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyRepository.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OAuth2SigningKeyRepository extends JpaRepository<OAuth2SigningKey, String> {
}
```

- [ ] **Step 4: Write the failing tests for `OAuth2SigningKeyService`**

Create `src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyServiceTest.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OAuth2SigningKeyServiceTest {

    private final OAuth2SigningKeyRepository repository = mock(OAuth2SigningKeyRepository.class);
    private final OAuth2SigningKeyService service = new OAuth2SigningKeyService(repository);

    @Test
    void returnsTheExistingKeyWhenOneIsAlreadyPersisted() throws Exception {
        RSAKey original = new RSAKeyGenerator(2048).keyID("existing-kid").generate();
        OAuth2SigningKey stored = new OAuth2SigningKey(OAuth2SigningKey.PRIMARY_KEY_ID, original.toJSONString());
        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)).thenReturn(Optional.of(stored));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result.getKeyID()).isEqualTo("existing-kid");
        assertThat(result.isPrivate()).isTrue();
    }

    @Test
    void generatesAndPersistsANewKeyWhenNoneExists() {
        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID)).thenReturn(Optional.empty());
        when(repository.saveAndFlush(any(OAuth2SigningKey.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result).isNotNull();
        assertThat(result.isPrivate()).isTrue();
        verify(repository, times(1)).saveAndFlush(any(OAuth2SigningKey.class));
    }

    @Test
    void recoversTheWinningKeyWhenTwoInstancesRaceToCreateIt() throws Exception {
        RSAKey winner = new RSAKeyGenerator(2048).keyID("winner-kid").generate();
        OAuth2SigningKey winnerRow = new OAuth2SigningKey(OAuth2SigningKey.PRIMARY_KEY_ID, winner.toJSONString());

        when(repository.findById(OAuth2SigningKey.PRIMARY_KEY_ID))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winnerRow));
        doThrow(new DataIntegrityViolationException("duplicate key"))
                .when(repository).saveAndFlush(any(OAuth2SigningKey.class));

        RSAKey result = service.getOrCreateSigningKey();

        assertThat(result.getKeyID()).isEqualTo("winner-kid");
    }
}
```

This test verifies the service's LOGIC (the two-call `findById` sequence, the conflict-recovery branch). It does not — and cannot, as a mocked unit test — prove the real Postgres-level unique-constraint race behaves this way; that's covered by this task's live verification step below.

- [ ] **Step 5: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=OAuth2SigningKeyServiceTest`
Expected: FAIL to compile — `OAuth2SigningKeyService` doesn't exist yet.

- [ ] **Step 6: Write `OAuth2SigningKeyService`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyService.java`:

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

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=OAuth2SigningKeyServiceTest`
Expected: PASS (3 tests).

- [ ] **Step 8: Wire `jwkSource()` in `SecurityConfig` to use the service**

Replace the full contents of `src/main/java/com/creditscore/platform/config/SecurityConfig.java` with (this removes the `com.nimbusds.jose.JOSEException`, `com.nimbusds.jose.jwk.RSAKey`, `com.nimbusds.jose.jwk.gen.RSAKeyGenerator`, and `java.util.UUID` imports — none are referenced anywhere else in this file — and adds one new import and one new bean parameter; everything else in the file is unchanged from its current content):

```java
package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2SigningKeyService;
import com.creditscore.platform.identity.auth.oauth2.OAuth2UsageMeteringFilter;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configurers.OAuth2AuthorizationServerConfigurer;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    /**
     * The only Authorization Server endpoints this platform implements. Must stay in sync
     * with {@link AuthorizationServerSettings}' defaults for the token, JWK set and
     * metadata endpoints.
     */
    static final String[] SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS = {
            "/oauth2/token", "/oauth2/jwks", "/.well-known/oauth-authorization-server"
    };

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(OAuth2SigningKeyService signingKeyService) {
        JWKSet jwkSet = new JWKSet(signingKeyService.getOrCreateSigningKey());
        return new ImmutableJWKSet<>(jwkSet);
    }

    @Bean
    public JwtDecoder jwtDecoder(JWKSource<SecurityContext> jwkSource) {
        return OAuth2AuthorizationServerConfiguration.jwtDecoder(jwkSource);
    }

    @Bean
    public AuthorizationServerSettings authorizationServerSettings() {
        return AuthorizationServerSettings.builder().build();
    }

    @Bean
    @Order(1)
    public SecurityFilterChain adminFilterChain(HttpSecurity http,
            @Value("${app.admin.platform-admin-token}") String platformAdminToken) throws Exception {
        AdminTokenFilter adminTokenFilter = new AdminTokenFilter(platformAdminToken);

        http
                .securityMatcher("/api/v1/admin/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(adminTokenFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().hasAuthority("PLATFORM_ADMIN"))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    /**
     * Deliberately does NOT use {@code OAuth2AuthorizationServerConfiguration.applyDefaultSecurity},
     * which sets {@code securityMatcher(configurer.getEndpointsMatcher())} — the FULL Spring
     * Authorization Server surface: {@code /oauth2/authorize}, {@code /oauth2/revoke},
     * {@code /oauth2/introspect}, {@code /oauth2/device_authorization},
     * {@code /oauth2/device_verification}. This platform implements only the
     * client-credentials grant, so it needs exactly three of those endpoints and has never
     * reviewed the rest.
     *
     * <p>Narrowing the {@code securityMatcher} (rather than leaving the matcher wide and
     * adding {@code anyRequest().denyAll()}) is what actually closes the surface. SAS
     * registers its endpoint filters at two different positions: the token, introspection,
     * revocation and device-authorization filters go in with
     * {@code addFilterAfter(..., AuthorizationFilter.class)} and so ARE governed by
     * {@code authorizeHttpRequests}, but the authorization-endpoint and
     * device-verification filters go in with
     * {@code addFilterBefore(..., AbstractPreAuthenticatedProcessingFilter.class)} and
     * therefore run BEFORE {@code AuthorizationFilter} ever evaluates a rule. A
     * {@code denyAll()} rule consequently cannot stop {@code GET /oauth2/authorize} from
     * reaching SAS's internal authorization-code validation, which fails with an
     * unhandled 500 for this app's clients (they declare no {@code redirectUris}). Because
     * this chain no longer matches those URIs at all, the filters simply never run for
     * them; {@link #oauth2DisabledEndpointsFilterChain} then denies them explicitly.
     *
     * <p>{@code permitAll} here is not "unauthenticated access": the token endpoint
     * authenticates the client itself via HTTP Basic in {@code OAuth2ClientAuthenticationFilter},
     * which is registered before {@code AuthorizationFilter} and so runs regardless of this
     * rule. {@code /oauth2/jwks} and the metadata document are public by specification.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfigurer authorizationServerConfigurer =
                new OAuth2AuthorizationServerConfigurer();

        http
                .securityMatcher(SUPPORTED_AUTHORIZATION_SERVER_ENDPOINTS)
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .with(authorizationServerConfigurer, Customizer.withDefaults());

        return http.build();
    }

    /**
     * Explicitly closes the rest of the Authorization Server's URI space. This chain
     * carries no SAS filters at all, so a request to an endpoint this platform does not
     * implement is denied by {@code AuthorizationFilter} without any SAS internal
     * processing running first.
     */
    @Bean
    @Order(3)
    public SecurityFilterChain oauth2DisabledEndpointsFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/oauth2/**", "/.well-known/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().denyAll())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(4)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, ConsumerRepository consumerRepository,
                                               PlatformTransactionManager transactionManager,
                                               UsageMeter usageMeter, JwtDecoder jwtDecoder) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter =
                new ApiKeyAuthFilter(consumerRepository, new TransactionTemplate(transactionManager), usageMeter);
        OAuth2UsageMeteringFilter oauth2UsageMeteringFilter = new OAuth2UsageMeteringFilter(consumerRepository,
                usageMeter, new TransactionTemplate(transactionManager));

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(oauth2UsageMeteringFilter, ApiKeyAuthFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt
                        .decoder(jwtDecoder)
                        .jwtAuthenticationConverter(new OAuth2ConsumerAuthenticationConverter(consumerRepository))))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(5)
    public SecurityFilterChain publicFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/swagger-ui/**", "/v3/api-docs/**", "/swagger-ui.html", "/actuator/health")
                .csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }

    private void unauthorized(jakarta.servlet.http.HttpServletRequest request,
                               jakarta.servlet.http.HttpServletResponse response,
                               Exception exception) throws IOException {
        response.setStatus(401);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Missing or invalid credentials\"}");
    }

    private void forbidden(jakarta.servlet.http.HttpServletRequest request,
                            jakarta.servlet.http.HttpServletResponse response,
                            org.springframework.security.access.AccessDeniedException exception) throws IOException {
        response.setStatus(403);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Consumer lacks required scope\"}");
    }
}
```

- [ ] **Step 9: Compile and run the full suite**

Run: `./mvnw clean test`
Expected: `BUILD SUCCESS`, every existing test still passes plus the 3 new ones.

- [ ] **Step 10: Live verification — single instance persists and reuses the key across a restart**

```bash
docker compose down -v
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run &
sleep 15
psql_check() { docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c "$1"; }
echo "Row count after first boot:"; psql_check "SELECT count(*) FROM oauth2_signing_keys;"
KID_BEFORE=$(curl -s http://localhost:8080/oauth2/jwks | python3 -c "import sys,json; print(json.load(sys.stdin)['keys'][0]['kid'])")
echo "kid before restart: $KID_BEFORE"
kill %1
wait %1 2>/dev/null
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run &
sleep 15
KID_AFTER=$(curl -s http://localhost:8080/oauth2/jwks | python3 -c "import sys,json; print(json.load(sys.stdin)['keys'][0]['kid'])")
echo "kid after restart: $KID_AFTER"
[ "$KID_BEFORE" = "$KID_AFTER" ] && echo "PASS: same key reused" || echo "FAIL: key regenerated"
kill %1
wait %1 2>/dev/null
```

Expected: exactly one row in `oauth2_signing_keys` after the first boot; `KID_BEFORE` equals `KID_AFTER` — the same key is reused across the restart, not regenerated.

- [ ] **Step 11: Live verification — two instances racing on an empty table converge on one key**

```bash
docker compose down -v
docker compose up -d postgres
sleep 3
SPRING_PROFILES_ACTIVE=seed SERVER_PORT=8080 ./mvnw spring-boot:run &
SPRING_PROFILES_ACTIVE=seed SERVER_PORT=8081 ./mvnw spring-boot:run &
sleep 20
KID_A=$(curl -s http://localhost:8080/oauth2/jwks | python3 -c "import sys,json; print(json.load(sys.stdin)['keys'][0]['kid'])")
KID_B=$(curl -s http://localhost:8081/oauth2/jwks | python3 -c "import sys,json; print(json.load(sys.stdin)['keys'][0]['kid'])")
echo "Instance A kid: $KID_A"
echo "Instance B kid: $KID_B"
[ "$KID_A" = "$KID_B" ] && echo "PASS: both instances converged on one key" || echo "FAIL: two different keys"
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c "SELECT count(*) FROM oauth2_signing_keys;"
kill %1 %2
wait 2>/dev/null
```

Expected: both instances report the same `kid` (one instance's insert won, the other's conflict-recovery path returned the winner), and exactly one row exists in `oauth2_signing_keys` — never two. If this doesn't hold, do not proceed to Step 12 — investigate and report the exact behavior observed rather than guessing at a fix; this is the one scenario in this task no unit test can substitute for.

- [ ] **Step 12: Commit**

```bash
git add src/main/resources/db/migration/V10__oauth2_signing_keys.sql \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKey.java \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyRepository.java \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyService.java \
  src/main/java/com/creditscore/platform/config/SecurityConfig.java \
  src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2SigningKeyServiceTest.java
git commit -m "Persist the OAuth2 signing key to Postgres instead of generating one per startup"
```

---

### Task 2: Authorization store persistence

**Files:**
- Create: `src/main/resources/db/migration/V11__oauth2_authorization_store.sql`
- Modify: `src/main/java/com/creditscore/platform/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `JpaRegisteredClientRepository` (existing, unchanged) as the `RegisteredClientRepository` dependency `JdbcOAuth2AuthorizationService` needs.
- Produces: an `OAuth2AuthorizationService` bean, auto-detected by Spring Authorization Server (per the Global Constraints entry above — no explicit wiring call needed). Task 3's cleanup job depends on the `oauth2_authorization` table this task's migration creates.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V11__oauth2_authorization_store.sql` (Spring Authorization Server's reference schema — extracted from `org/springframework/security/oauth2/server/authorization/oauth2-authorization-schema.sql` inside the `spring-security-oauth2-authorization-server-1.3.3.jar` — with every `blob` column changed to `text`, per that schema file's own header comment: *"If using PostgreSQL, update ALL columns defined with 'blob' to 'text'"*):

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

-- Not part of Spring Authorization Server's reference schema. Added because
-- OAuth2AuthorizationCleanupJob (Task 3) runs a WHERE access_token_expires_at < ?
-- delete on a schedule, and this table is scanned by that predicate far more often
-- than by anything else this app does with it.
CREATE INDEX idx_oauth2_authorization_access_token_expires_at
    ON oauth2_authorization (access_token_expires_at);
```

- [ ] **Step 2: Add the `authorizationService` bean to `SecurityConfig`**

In `src/main/java/com/creditscore/platform/config/SecurityConfig.java`, add these imports (alphabetically among the existing import block):

```java
import com.fasterxml.jackson.databind.ObjectMapper;
```

```java
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.jackson2.SecurityJackson2Modules;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.jackson2.OAuth2AuthorizationServerJackson2Module;
```

Add this new bean method immediately after `authorizationServerSettings()` and before `adminFilterChain(...)`:

```java
    @Bean
    public OAuth2AuthorizationService authorizationService(JdbcTemplate jdbcTemplate,
                                                             RegisteredClientRepository registeredClientRepository) {
        JdbcOAuth2AuthorizationService service =
                new JdbcOAuth2AuthorizationService(jdbcTemplate, registeredClientRepository);

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModules(
                SecurityJackson2Modules.getModules(JdbcOAuth2AuthorizationService.class.getClassLoader()));
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

`registeredClientRepository` resolves to the existing `JpaRegisteredClientRepository` `@Component` — it's the only bean of that type in the context, so no qualifier is needed.

- [ ] **Step 3: Compile and run the full suite**

Run: `./mvnw clean test`
Expected: `BUILD SUCCESS`. No existing test touches `SecurityConfig`, so nothing should change here beyond compilation succeeding.

- [ ] **Step 4: Live verification — a real token issuance writes a real row**

```bash
docker compose down -v
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run &
sleep 15

PROVISION=$(curl -s -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Persistence Smoke Test","scopes":["SCORE_READ"]}')
CLIENT_ID=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientId'])")
CLIENT_SECRET=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientSecret'])")

curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials&scope=SCORE_READ' \
  http://localhost:8080/oauth2/token > /dev/null

docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -c \
  "SELECT registered_client_id, principal_name, authorization_grant_type, authorized_scopes, \
   access_token_expires_at IS NOT NULL AS has_expiry FROM oauth2_authorization;"

kill %1
wait %1 2>/dev/null
```

Expected: exactly one row, `authorization_grant_type = 'client_credentials'`, `authorized_scopes = 'SCORE_READ'`, `has_expiry = t`. No stack trace or exception in the app's stdout during the `/oauth2/token` call (a Jackson serialization failure from a misconfigured `ObjectMapper` would surface here).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/db/migration/V11__oauth2_authorization_store.sql \
  src/main/java/com/creditscore/platform/config/SecurityConfig.java
git commit -m "Persist the OAuth2 authorization store to Postgres via JdbcOAuth2AuthorizationService"
```

---

### Task 3: Authorization-store cleanup job

**Files:**
- Modify: `src/main/resources/application.yml`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2AuthorizationCleanupJob.java`

**Interfaces:**
- Consumes: `oauth2_authorization` table (Task 2), `@EnableScheduling` (already active on `PlatformApplication`, no change needed there).

- [ ] **Step 1: Add the cron property**

In `src/main/resources/application.yml`, add `authorization-cleanup-cron` inside the existing `app.oauth2` block (after `access-token-ttl-minutes`):

```yaml
  oauth2:
    # Access-token lifetime for the client_credentials grant. Kept deliberately short:
    # this grant issues no refresh token and the platform has no revocation endpoint, so
    # natural expiry is the only way an issued token stops working.
    access-token-ttl-minutes: 60
    # Every hour at :30 — offset from app.batch.sync-cron (every 6 hours on the hour) so
    # the two scheduled jobs don't contend for the same moment.
    authorization-cleanup-cron: "0 30 * * * *"
```

- [ ] **Step 2: Write `OAuth2AuthorizationCleanupJob`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2AuthorizationCleanupJob.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/**
 * Spring Authorization Server's JDBC store has no built-in eviction — moving it off
 * heap (see JdbcOAuth2AuthorizationService in SecurityConfig) stopped it from growing
 * without bound only if something deletes expired rows. This platform only ever
 * issues client_credentials tokens, so authorization_code/refresh_token/device_code
 * columns are always NULL and access_token_expires_at alone is a complete predicate
 * for "this row is stale."
 */
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

- [ ] **Step 3: Compile and run the full suite**

Run: `./mvnw clean test`
Expected: `BUILD SUCCESS`.

- [ ] **Step 4: Live verification — the job actually deletes expired rows and leaves fresh ones alone**

```bash
docker compose down -v
docker compose up -d postgres
# Override the cron to fire every 10 seconds for this verification run only — not committed.
SPRING_PROFILES_ACTIVE=seed APP_OAUTH2_AUTHORIZATION_CLEANUP_CRON="*/10 * * * * *" ./mvnw spring-boot:run &
sleep 15

PROVISION=$(curl -s -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Cleanup Smoke Test","scopes":["SCORE_READ"]}')
CLIENT_ID=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientId'])")
CLIENT_SECRET=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientSecret'])")

curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials&scope=SCORE_READ' \
  http://localhost:8080/oauth2/token > /dev/null

# Force one row into the past so the next cleanup pass sweeps it; a second, freshly
# issued token proves the job does not delete unexpired rows too.
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -c \
  "UPDATE oauth2_authorization SET access_token_expires_at = now() - interval '1 hour';"

curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials&scope=SCORE_READ' \
  http://localhost:8080/oauth2/token > /dev/null

echo "Before cleanup pass:"
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c \
  "SELECT count(*) FROM oauth2_authorization;"

sleep 15

echo "After cleanup pass:"
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c \
  "SELECT count(*) FROM oauth2_authorization;"

kill %1
wait %1 2>/dev/null
```

Expected: 2 rows before the cleanup pass, 1 row after (the manually-expired one is gone, the fresh one survives).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/application.yml \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2AuthorizationCleanupJob.java
git commit -m "Add scheduled cleanup for expired OAuth2 authorization rows"
```

---

### Task 4: Documentation and final verification

**Files:**
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`

**Interfaces:**
- None — this task documents Tasks 1-3 and does a final end-to-end check.

- [ ] **Step 1: Update `README.md`'s Known limitations section**

In `README.md`, replace the full `## Known limitations` section (the bullet list, from the `- **The RSA signing key is generated fresh...` bullet through the `- **Usage is metered per credential...` bullet) with:

```markdown
## Known limitations

These are accepted MVP trade-offs, not oversights.

- **No token revocation before natural expiry.** There is no `/oauth2/revoke` endpoint.
  Suspending or revoking a `Consumer` stops it minting *new* tokens immediately
  (`JpaRegisteredClientRepository` returns `null` for a non-`ACTIVE` consumer), but a
  token already in a caller's hands stays valid until it expires. The access-token TTL
  (`app.oauth2.access-token-ttl-minutes`) is the only bound on that window.
- **No client-secret rotation flow.** A secret is shown exactly once, at provisioning.
  Replacing a compromised one means provisioning a new consumer; there is no way to
  issue a second secret and retire the first without downtime for that client.
- **No signing-key rotation.** The RSA key is persisted (`oauth2_signing_keys`,
  generated once and reused indefinitely across restarts and instances — see
  `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md`), which solved the
  "every restart invalidates every token" and "two instances reject each other's
  tokens" problems. There is still no mechanism to rotate to a new key while
  continuing to accept tokens signed by an old one: replacing the stored key today
  invalidates every outstanding token at once, the same way a restart used to.
- **Usage is metered per credential, not deduplicated across them.** A request that
  presents both `X-API-Key` and a `Bearer` token is recorded by both metering paths. No
  product decision has been made about which credential should win for billing.
```

- [ ] **Step 2: Update `ARCHITECTURE.md`'s Known limitations section**

In `ARCHITECTURE.md`, replace the full `## Known limitations` section (from the `Accepted MVP trade-offs...` intro line through the `` `OAuth2AuthorizationService` is only useful alongside a persisted signing key.`` closing sentence) with:

```markdown
## Known limitations

Accepted MVP trade-offs in the auth layer. README.md carries the operator-facing
version of this list.

| Limitation | Consequence | Blocks |
|------------|-------------|--------|
| No token revocation endpoint | A non-`ACTIVE` `Consumer` cannot mint new tokens, but an already-issued token stays valid until it expires | Prompt credential compromise response |
| No client-secret rotation flow | The secret is shown once at provisioning; replacing it means provisioning a new consumer | Zero-downtime credential rotation |
| No signing-key rotation | The RSA key (persisted in `oauth2_signing_keys`) is reused indefinitely; replacing it invalidates every outstanding token at once | Zero-downtime key rotation |

The signing key and the OAuth2 authorization store are both persisted in Postgres as
of `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md` — this platform no
longer has a row in this table for either "invalidates every token on restart" or
"grows without bound," and can run more than one instance.
```

- [ ] **Step 3: Final full-suite and fresh-container verification**

```bash
./mvnw clean test
docker compose down -v
docker compose --profile full up --build -d
sleep 30
curl -i http://localhost:8080/swagger-ui.html
PROVISION=$(curl -s -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Final Smoke Test","scopes":["SCORE_READ"]}')
echo "$PROVISION"
CLIENT_ID=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientId'])")
CLIENT_SECRET=$(echo "$PROVISION" | python3 -c "import sys,json; print(json.load(sys.stdin)['clientSecret'])")
curl -s -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials&scope=SCORE_READ' \
  http://localhost:8080/oauth2/token
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c \
  "SELECT count(*) FROM oauth2_signing_keys;"
docker exec -i credit-scoring-postgres psql -U credit_scoring -d credit_scoring -t -c \
  "SELECT count(*) FROM oauth2_authorization;"
docker compose --profile full down
```

Expected: `./mvnw clean test` passes in full (should be at 42 tests: the 39 from before this plan, plus the 3 new `OAuth2SigningKeyServiceTest` cases). The containerized `app` service starts cleanly (all 11 migrations, `V1`–`V11`, apply from empty schema), serves Swagger UI, provisions a consumer, and issues a real token — with both new tables populated (1 row in `oauth2_signing_keys`, 1 row in `oauth2_authorization`).

- [ ] **Step 4: Commit**

```bash
git add README.md ARCHITECTURE.md
git commit -m "Document persisted OAuth2 signing key and authorization store"
```

---

## Self-Review

**Spec coverage:** Every section of `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md` maps to a task — signing key persistence with the race-handling pattern (Task 1), authorization store persistence with the Jackson wiring requirement (Task 2), the cleanup job (Task 3), the "not building revocation/rotation" non-goals (documented as still-open limitations in Task 4, not silently dropped).

**Placeholder scan:** No `TBD`/`TODO`/"add appropriate handling" language anywhere — every step has complete, literal code, SQL, or shell commands with concrete expected output.

**Type/signature consistency across tasks:** `OAuth2SigningKey.PRIMARY_KEY_ID` (Task 1) is the only place this literal `"primary"` string is defined; every other reference in Task 1 uses the constant, not a re-typed literal. `OAuth2SigningKeyService.getOrCreateSigningKey() -> RSAKey` (Task 1) is called exactly once, in Task 1's own `SecurityConfig.jwkSource()` change — Task 2 doesn't touch this method. Task 2's `authorizationService(...)` bean signature (`JdbcTemplate`, `RegisteredClientRepository`) matches real, already-resolved Spring types — no new Maven dependency required, confirmed via `dependency:tree` before this plan was written. Task 3's `OAuth2AuthorizationCleanupJob` references the exact table name (`oauth2_authorization`) and column name (`access_token_expires_at`) Task 2's migration creates — verified identical spelling in both tasks' SQL/Java. Task 4's doc edits quote the exact current README/ARCHITECTURE text being replaced, read directly from the live files before this plan was written, not reconstructed from memory.

No gaps found.
