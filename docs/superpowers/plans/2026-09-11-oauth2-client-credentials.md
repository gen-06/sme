# OAuth2 Client-Credentials Auth Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a second, coexisting authentication path — OAuth2 client-credentials via a self-hosted Spring Authorization Server — alongside the existing API-key auth, plus a `PLATFORM_ADMIN_TOKEN`-guarded endpoint to provision OAuth2 clients, with zero changes to any existing controller.

**Architecture:** `Consumer` rows already carry unused `oauth_client_id`/`oauth_client_secret_hash` columns; a new `JpaRegisteredClientRepository` adapts those rows into Spring Authorization Server's `RegisteredClient` at lookup time (no new client table). Token issuance is handled entirely by Spring Authorization Server's auto-configured `/oauth2/token` endpoint. Token validation extends the existing `apiFilterChain` with `.oauth2ResourceServer(...)`, using a custom `Converter<Jwt, AbstractAuthenticationToken>` that maps the JWT's `scope` claim to bare (unprefixed) authorities and loads the real `Consumer` entity (via a `consumer_id` claim stamped at issuance by a token customizer) as the authentication principal — so `@AuthenticationPrincipal Consumer` and every `@PreAuthorize("hasAuthority(...)")` check keeps working identically regardless of which credential a request presents.

**Tech Stack:** Spring Boot 3.3.5 (unchanged parent). New dependencies, versions resolved by Boot's dependency-managed BOM (verified empirically against the local Maven repository — no explicit `<version>` needed, matching every other Boot-managed dependency in this `pom.xml`):
- `spring-security-oauth2-authorization-server` → resolves to `1.3.3`
- `spring-security-oauth2-resource-server` → resolves to `6.3.4`
- transitively: `com.nimbusds:nimbus-jose-jwt:9.39.3` (RSA key generation for JWT signing)

**Spec:** `docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md`

## Global Constraints

- Coexist, don't replace: `X-API-Key` auth (`ApiKeyAuthFilter`) keeps working unchanged; this plan only adds a second path.
- No `@SpringBootTest`/`@DataJpaTest` anywhere in this repo — an established, deliberate convention (see `README.md`'s Tests section). Every test added by this plan is a pure JUnit 5 + Mockito + AssertJ unit test with no Spring context, matching the style of the existing `scoring/rulesbased/*Test.java` files.
- New code lives in `identity.auth` (auth-token types, mirroring the existing `ApiKeyAuthenticationToken`) and `identity.auth.oauth2` (everything OAuth2-specific — the package already exists as a stub per `ARCHITECTURE.md`'s extension-points table).
- Authorities are always bare `ConsumerScope` names (e.g. `SCORE_READ`), never Spring's default `SCOPE_`-prefixed form, on both the API-key and OAuth2 paths.
- Access tokens are JWTs, self-signed with an in-memory RSA key generated at startup (a documented MVP limitation — not persisted, not rotatable yet).
- `RegisteredClient.getId()` is always the `Consumer`'s own UUID string — this is what lets the token customizer stamp `consumer_id` from `context.getRegisteredClient().getId()` without a second database lookup at issuance time.
- Admin provisioning (`POST /api/v1/admin/consumers`) is guarded by a `PLATFORM_ADMIN_TOKEN` env var (`app.admin.platform-admin-token` in `application.yml`), not a `ConsumerScope` — it is a platform-operator action, not a per-consumer permission.
- The admin filter chain never touches `UsageMeter`/`usage_records` — there is no authenticated `Consumer` for an admin request, and `UsageRecord.consumerId` is a `NOT NULL` column.
- Flyway migrations continue sequentially: this plan adds exactly one, `V8__oauth2_client_support.sql`.

---

### Task 1: Dependencies, schema, and `Consumer`/`ConsumerRepository` foundation

**Files:**
- Modify: `pom.xml`
- Create: `src/main/resources/db/migration/V8__oauth2_client_support.sql`
- Modify: `src/main/java/com/creditscore/platform/identity/consumer/Consumer.java`
- Modify: `src/main/java/com/creditscore/platform/identity/consumer/ConsumerRepository.java`
- Test: `src/test/java/com/creditscore/platform/identity/consumer/ConsumerTest.java`

**Interfaces:**
- Produces: `Consumer.forOAuth2Client(String name, String contactEmail, Set<ConsumerScope> scopes)` — static factory for an OAuth2-only consumer (no API key). `Consumer.getOauthClientId()`/`setOauthClientId(String)`, `Consumer.getOauthClientSecretHash()`/`setOauthClientSecretHash(String)`. `ConsumerRepository.findByOauthClientId(String)`.

- [ ] **Step 1: Add the two new dependencies to `pom.xml`**

Add these two `<dependency>` blocks to the `<dependencies>` section, right after the existing `spring-boot-starter-security` dependency. No `<version>` — Spring Boot 3.3.5's parent BOM manages both (confirmed by resolving them against this exact parent version: they come in at `1.3.3` and `6.3.4` respectively).

```xml
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-oauth2-authorization-server</artifactId>
    </dependency>
    <dependency>
      <groupId>org.springframework.security</groupId>
      <artifactId>spring-security-oauth2-resource-server</artifactId>
    </dependency>
```

- [ ] **Step 2: Run `./mvnw clean compile` to confirm the new dependencies resolve**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`. This pulls the two new jars (and `nimbus-jose-jwt` transitively) with no code changes yet, isolating dependency resolution from any later compile error.

- [ ] **Step 3: Write the migration**

Create `src/main/resources/db/migration/V8__oauth2_client_support.sql`:

```sql
-- An OAuth2-only consumer (provisioned via POST /api/v1/admin/consumers) has no API
-- key at all. Existing rows are unaffected: every current row already has a non-null
-- value here, and Postgres's UNIQUE constraint tolerates multiple NULLs under
-- standard SQL null semantics — the same reasoning applies to the new constraint
-- below on oauth_client_id.
ALTER TABLE consumers ALTER COLUMN api_key_hash DROP NOT NULL;

-- oauth_client_id has existed since V2 but was never enforced unique or indexed —
-- it was unused until now. RegisteredClientRepository.findByClientId depends on
-- lookups here being unambiguous.
ALTER TABLE consumers ADD CONSTRAINT consumers_oauth_client_id_key UNIQUE (oauth_client_id);
```

- [ ] **Step 4: Write the failing test for the new `Consumer` factory and accessors**

Create `src/test/java/com/creditscore/platform/identity/consumer/ConsumerTest.java`:

```java
package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ConsumerTest {

    @Test
    void forOAuth2ClientCreatesActiveConsumerWithNoApiKey() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test",
                Set.of(ConsumerScope.SCORE_READ));

        assertThat(consumer.getName()).isEqualTo("Acme Lender");
        assertThat(consumer.getContactEmail()).isEqualTo("ops@acme.test");
        assertThat(consumer.getStatus()).isEqualTo(ConsumerStatus.ACTIVE);
        assertThat(consumer.getApiKeyHash()).isNull();
        assertThat(consumer.getScopes()).containsExactly(ConsumerScope.SCORE_READ);
    }

    @Test
    void oauthClientCredentialsAreSettableAfterConstruction() {
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", null, Set.of(ConsumerScope.SCORE_READ));

        consumer.setOauthClientId("client_abc123");
        consumer.setOauthClientSecretHash("{bcrypt}$2a$10$examplehasheddata");

        assertThat(consumer.getOauthClientId()).isEqualTo("client_abc123");
        assertThat(consumer.getOauthClientSecretHash()).isEqualTo("{bcrypt}$2a$10$examplehasheddata");
    }
}
```

- [ ] **Step 5: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ConsumerTest`
Expected: FAIL to compile — `forOAuth2Client`, `getOauthClientId`, `setOauthClientId`, `setOauthClientSecretHash` don't exist yet.

- [ ] **Step 6: Add the factory method and accessors to `Consumer`**

In `src/main/java/com/creditscore/platform/identity/consumer/Consumer.java`, add these methods (the existing constructor, fields, and other getters are unchanged):

```java
    public static Consumer forOAuth2Client(String name, String contactEmail, Set<ConsumerScope> scopes) {
        Consumer consumer = new Consumer();
        consumer.name = name;
        consumer.contactEmail = contactEmail;
        consumer.status = ConsumerStatus.ACTIVE;
        consumer.scopes = new HashSet<>(scopes);
        return consumer;
    }

    public String getOauthClientId() {
        return oauthClientId;
    }

    public void setOauthClientId(String oauthClientId) {
        this.oauthClientId = oauthClientId;
    }

    public String getOauthClientSecretHash() {
        return oauthClientSecretHash;
    }

    public void setOauthClientSecretHash(String oauthClientSecretHash) {
        this.oauthClientSecretHash = oauthClientSecretHash;
    }
```

Add these methods after the existing `getConsumerId()` method, before the closing brace of the class.

- [ ] **Step 7: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ConsumerTest`
Expected: PASS (2 tests).

- [ ] **Step 8: Add the derived query to `ConsumerRepository`**

In `src/main/java/com/creditscore/platform/identity/consumer/ConsumerRepository.java`, add this method inside the interface, after `findByApiKeyHash`:

```java
    Optional<Consumer> findByOauthClientId(String oauthClientId);
```

- [ ] **Step 9: Verify the full existing suite and a fresh migration apply still pass**

Run:
```bash
docker compose down -v
docker compose up -d postgres
./mvnw test
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run
```
Expected: `./mvnw test` passes (including the two new `ConsumerTest` cases and every pre-existing test). The `spring-boot:run` log shows Flyway applying `V1`–`V8` cleanly and the seed data loading with no errors (seed consumers still have API keys — `V8` only relaxes the constraint, it doesn't touch existing rows). Stop the app (`Ctrl+C`) once confirmed.

- [ ] **Step 10: Commit**

```bash
git add pom.xml src/main/resources/db/migration/V8__oauth2_client_support.sql \
  src/main/java/com/creditscore/platform/identity/consumer/Consumer.java \
  src/main/java/com/creditscore/platform/identity/consumer/ConsumerRepository.java \
  src/test/java/com/creditscore/platform/identity/consumer/ConsumerTest.java
git commit -m "Add OAuth2 dependencies, schema, and Consumer foundation for client-credentials auth"
```

---

### Task 2: Admin provisioning endpoint

**Files:**
- Create: `src/main/java/com/creditscore/platform/identity/auth/AdminTokenFilter.java`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ClientCredentialGenerator.java`
- Create: `src/main/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningService.java`
- Create: `src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionRequest.java`
- Create: `src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionResponse.java`
- Create: `src/main/java/com/creditscore/platform/api/controller/AdminConsumerController.java`
- Modify: `src/main/java/com/creditscore/platform/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yml`
- Test: `src/test/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningServiceTest.java`

**Interfaces:**
- Consumes: `Consumer.forOAuth2Client(...)`, `Consumer.setOauthClientId/setOauthClientSecretHash` (Task 1).
- Produces: `ConsumerProvisioningService.provision(String name, String contactEmail, Set<ConsumerScope> scopes) -> ConsumerProvisioningService.ProvisionedConsumer(UUID consumerId, String clientId, String clientSecret)` — later tasks' end-to-end verification (Task 6) depends on this exact return shape. A `PasswordEncoder` bean is added to `SecurityConfig` in this task; Task 5 relies on it being present for Spring Authorization Server's client-secret verification.
- **Note on verification scope**: a client provisioned by this task cannot yet obtain a real OAuth2 token — `/oauth2/token` doesn't exist until Task 5. This task's own verification only confirms the `Consumer` row and hashing are correct; end-to-end token issuance is verified in Task 6.

- [ ] **Step 1: Write the failing test for `ConsumerProvisioningService`**

Create `src/test/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningServiceTest.java`:

```java
package com.creditscore.platform.identity.consumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConsumerProvisioningServiceTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final PasswordEncoder passwordEncoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    private final ConsumerProvisioningService service =
            new ConsumerProvisioningService(consumerRepository, passwordEncoder);

    @Test
    void provisionGeneratesClientCredentialsAndStoresOnlyTheHash() {
        when(consumerRepository.save(any(Consumer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.provision("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));

        assertThat(result.clientId()).startsWith("client_");
        assertThat(result.clientSecret()).startsWith("secret_");

        ArgumentCaptor<Consumer> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(consumerRepository).save(captor.capture());
        Consumer saved = captor.getValue();

        assertThat(saved.getOauthClientId()).isEqualTo(result.clientId());
        assertThat(saved.getOauthClientSecretHash()).isNotEqualTo(result.clientSecret());
        assertThat(passwordEncoder.matches(result.clientSecret(), saved.getOauthClientSecretHash())).isTrue();
        assertThat(saved.getScopes()).containsExactly(ConsumerScope.SCORE_READ);
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw test -Dtest=ConsumerProvisioningServiceTest`
Expected: FAIL to compile — `ConsumerProvisioningService` and `OAuth2ClientCredentialGenerator` don't exist yet.

- [ ] **Step 3: Write `OAuth2ClientCredentialGenerator`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ClientCredentialGenerator.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import java.security.SecureRandom;
import java.util.Base64;

public final class OAuth2ClientCredentialGenerator {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OAuth2ClientCredentialGenerator() {
    }

    public static String generateClientId() {
        return "client_" + randomToken();
    }

    public static String generateClientSecret() {
        return "secret_" + randomToken();
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
```

- [ ] **Step 4: Write `ConsumerProvisioningService`**

Create `src/main/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningService.java`:

```java
package com.creditscore.platform.identity.consumer;

import com.creditscore.platform.identity.auth.oauth2.OAuth2ClientCredentialGenerator;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;
import java.util.UUID;

@Service
public class ConsumerProvisioningService {

    private final ConsumerRepository consumerRepository;
    private final PasswordEncoder passwordEncoder;

    public ConsumerProvisioningService(ConsumerRepository consumerRepository, PasswordEncoder passwordEncoder) {
        this.consumerRepository = consumerRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public ProvisionedConsumer provision(String name, String contactEmail, Set<ConsumerScope> scopes) {
        String clientId = OAuth2ClientCredentialGenerator.generateClientId();
        String rawClientSecret = OAuth2ClientCredentialGenerator.generateClientSecret();

        Consumer consumer = Consumer.forOAuth2Client(name, contactEmail, scopes);
        consumer.setOauthClientId(clientId);
        consumer.setOauthClientSecretHash(passwordEncoder.encode(rawClientSecret));

        Consumer saved = consumerRepository.save(consumer);
        return new ProvisionedConsumer(saved.getId(), clientId, rawClientSecret);
    }

    public record ProvisionedConsumer(UUID consumerId, String clientId, String clientSecret) {
    }
}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw test -Dtest=ConsumerProvisioningServiceTest`
Expected: PASS.

- [ ] **Step 6: Write the DTOs**

Create `src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionRequest.java`:

```java
package com.creditscore.platform.api.dto;

import com.creditscore.platform.identity.consumer.ConsumerScope;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.Set;

public record ConsumerProvisionRequest(
        @NotBlank String name,
        String contactEmail,
        @NotEmpty Set<ConsumerScope> scopes) {
}
```

Create `src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionResponse.java`:

```java
package com.creditscore.platform.api.dto;

import java.util.UUID;

public record ConsumerProvisionResponse(UUID consumerId, String clientId, String clientSecret) {
}
```

- [ ] **Step 7: Write `AdminConsumerController`**

Create `src/main/java/com/creditscore/platform/api/controller/AdminConsumerController.java`:

```java
package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.ConsumerProvisionRequest;
import com.creditscore.platform.api.dto.ConsumerProvisionResponse;
import com.creditscore.platform.identity.consumer.ConsumerProvisioningService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/consumers")
public class AdminConsumerController {

    private final ConsumerProvisioningService provisioningService;

    public AdminConsumerController(ConsumerProvisioningService provisioningService) {
        this.provisioningService = provisioningService;
    }

    @PostMapping
    public ResponseEntity<ConsumerProvisionResponse> provision(@Valid @RequestBody ConsumerProvisionRequest request) {
        var provisioned = provisioningService.provision(request.name(), request.contactEmail(), request.scopes());
        return ResponseEntity.status(HttpStatus.CREATED).body(
                new ConsumerProvisionResponse(provisioned.consumerId(), provisioned.clientId(),
                        provisioned.clientSecret()));
    }
}
```

- [ ] **Step 8: Write `AdminTokenFilter`**

Create `src/main/java/com/creditscore/platform/identity/auth/AdminTokenFilter.java`:

```java
package com.creditscore.platform.identity.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

public class AdminTokenFilter extends OncePerRequestFilter {

    public static final String ADMIN_TOKEN_HEADER = "X-Platform-Admin-Token";

    private final String expectedToken;

    public AdminTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String presentedToken = request.getHeader(ADMIN_TOKEN_HEADER);
        if (presentedToken != null && constantTimeEquals(presentedToken, expectedToken)) {
            SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                    "platform-admin", null, List.of(new SimpleGrantedAuthority("PLATFORM_ADMIN"))));
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }
}
```

- [ ] **Step 9: Add the admin filter chain, `PasswordEncoder` bean, and explicit `@Order` to `SecurityConfig`**

Replace the full contents of `src/main/java/com/creditscore/platform/config/SecurityConfig.java` with:

```java
package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
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

    @Bean
    @Order(3)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, ConsumerRepository consumerRepository,
                                               PlatformTransactionManager transactionManager,
                                               UsageMeter usageMeter) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter =
                new ApiKeyAuthFilter(consumerRepository, new TransactionTemplate(transactionManager), usageMeter);

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(this::unauthorized)
                        .accessDeniedHandler(this::forbidden));

        return http.build();
    }

    @Bean
    @Order(4)
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

`/api/v1/admin/**` is a subset of `/api/**`. `adminFilterChain` at `@Order(1)` is evaluated before `apiFilterChain` at `@Order(3)`, so an admin request is fully handled by the admin chain and never reaches `apiFilterChain` — this is what keeps admin requests out of the API-key/scope-based authorization and out of usage metering (the admin chain never constructs an `ApiKeyAuthFilter` or touches `UsageMeter`). `@Order(2)` is intentionally left free for Task 5's Authorization Server chain.

The `unauthorized()` message changed from "Missing or invalid X-API-Key" to "Missing or invalid credentials" since `apiFilterChain` will accept either credential type from Task 5 onward — update this now so Task 5 doesn't need to touch this line again.

- [ ] **Step 10: Add the admin token property to `application.yml`**

In `src/main/resources/application.yml`, add a new top-level `app.admin` block. Insert it after the existing `app.batch` block (before `springdoc`):

```yaml
  admin:
    # Local-dev default matches this project's existing low-ceremony pattern (the
    # demo API key is likewise just logged at startup, not console-issued). Override
    # via the PLATFORM_ADMIN_TOKEN env var for anything beyond a laptop.
    platform-admin-token: ${PLATFORM_ADMIN_TOKEN:local-dev-admin-token}
```

- [ ] **Step 11: Verify manually with curl**

```bash
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run &
sleep 15

# Wrong/missing token -> 401
curl -i -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -d '{"name":"Acme Lender","contactEmail":"ops@acme.test","scopes":["SCORE_READ"]}'

# Correct token -> 201 with consumerId/clientId/clientSecret
curl -i -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Acme Lender","contactEmail":"ops@acme.test","scopes":["SCORE_READ"]}'
```

Expected: first call returns `401` with the `unauthorized` JSON body. Second call returns `201` with a real `consumerId`/`clientId`/`clientSecret`. Then confirm the stored hash, not the raw secret, was persisted:

```bash
docker exec -it credit-scoring-postgres psql -U credit_scoring -d credit_scoring \
  -c "SELECT oauth_client_id, left(oauth_client_secret_hash, 10) FROM consumers ORDER BY created_at DESC LIMIT 1;"
```

Expected: `oauth_client_secret_hash` starts with `{bcrypt}`, and does not match the raw `clientSecret` from the curl response. Stop the app (`kill %1` or find its PID and `kill` it).

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/creditscore/platform/identity/auth/AdminTokenFilter.java \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ClientCredentialGenerator.java \
  src/main/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningService.java \
  src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionRequest.java \
  src/main/java/com/creditscore/platform/api/dto/ConsumerProvisionResponse.java \
  src/main/java/com/creditscore/platform/api/controller/AdminConsumerController.java \
  src/main/java/com/creditscore/platform/config/SecurityConfig.java \
  src/main/resources/application.yml \
  src/test/java/com/creditscore/platform/identity/consumer/ConsumerProvisioningServiceTest.java
git commit -m "Add PLATFORM_ADMIN_TOKEN-guarded consumer provisioning endpoint"
```

---

### Task 3: Token claim customizer and resource-server converter

**Files:**
- Create: `src/main/java/com/creditscore/platform/identity/auth/OAuth2ConsumerAuthenticationToken.java`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerTokenCustomizer.java`
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverter.java`
- Test: `src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverterTest.java`

**Interfaces:**
- Consumes: `ConsumerRepository.findById` (existing, from `JpaRepository`), `Consumer` (Task 1).
- Produces: `OAuth2ConsumerAuthenticationToken(Consumer consumer, Collection<? extends GrantedAuthority> authorities)`, `OAuth2ConsumerAuthenticationConverter(ConsumerRepository consumerRepository) implements Converter<Jwt, AbstractAuthenticationToken>` — Task 5 wires this converter into `apiFilterChain`'s `.oauth2ResourceServer(...)`. `OAuth2ConsumerTokenCustomizer` reads `consumer_id` from `context.getRegisteredClient().getId()` (Task 4 guarantees this equals the `Consumer`'s own UUID string) and writes it as a JWT claim also named `consumer_id` — this is the exact claim name `OAuth2ConsumerAuthenticationConverter` reads back.

- [ ] **Step 1: Write the failing tests for `OAuth2ConsumerAuthenticationConverter`**

Create `src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverterTest.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAuth2ConsumerAuthenticationConverterTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final OAuth2ConsumerAuthenticationConverter converter =
            new OAuth2ConsumerAuthenticationConverter(consumerRepository);

    @Test
    void mapsScopeClaimToBareAuthoritiesAndLoadsConsumerAsPrincipal() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ TRANSACTION_READ")
                .claim("consumer_id", consumerId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        AbstractAuthenticationToken token = converter.convert(jwt);

        assertThat(token.getPrincipal()).isSameAs(consumer);
        assertThat(token.getAuthorities())
                .extracting(Object::toString)
                .containsExactlyInAnyOrder("SCORE_READ", "TRANSACTION_READ");
    }

    @Test
    void missingConsumerIdClaimIsRejected() {
        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void unknownConsumerIdIsRejected() {
        UUID consumerId = UUID.randomUUID();
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.empty());

        Jwt jwt = Jwt.withTokenValue("token-value")
                .header("alg", "RS256")
                .claim("scope", "SCORE_READ")
                .claim("consumer_id", consumerId.toString())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();

        assertThatThrownBy(() -> converter.convert(jwt)).isInstanceOf(BadCredentialsException.class);
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=OAuth2ConsumerAuthenticationConverterTest`
Expected: FAIL to compile — none of the three new classes exist yet.

- [ ] **Step 3: Write `OAuth2ConsumerAuthenticationToken`**

Create `src/main/java/com/creditscore/platform/identity/auth/OAuth2ConsumerAuthenticationToken.java`:

```java
package com.creditscore.platform.identity.auth;

import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;

/**
 * Authentication for a request bearing a valid OAuth2 access token. Authorities come
 * from the token's own "scope" claim (what was granted at issuance), not a live
 * Consumer.getScopes() lookup — an already-issued token isn't re-checked against
 * later scope changes (see the OAuth2 design spec's "known MVP limitations"). The
 * principal is still the real Consumer, matching ApiKeyAuthenticationToken, so
 * @AuthenticationPrincipal Consumer works identically for both auth methods.
 */
public class OAuth2ConsumerAuthenticationToken extends AbstractAuthenticationToken {

    private final Consumer consumer;

    public OAuth2ConsumerAuthenticationToken(Consumer consumer, Collection<? extends GrantedAuthority> authorities) {
        super(authorities);
        this.consumer = consumer;
        setAuthenticated(true);
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public Object getPrincipal() {
        return consumer;
    }

    public Consumer getConsumer() {
        return consumer;
    }
}
```

- [ ] **Step 4: Write `OAuth2ConsumerAuthenticationConverter`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverter.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.auth.OAuth2ConsumerAuthenticationToken;
import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import java.util.Collection;
import java.util.UUID;

/**
 * Two independent jobs, matching the design spec: (1) map the JWT's "scope" claim to
 * bare authority strings — delegating to Spring's own JwtGrantedAuthoritiesConverter
 * with an empty prefix, rather than hand-parsing, since that converter already
 * handles both the space-delimited-string and collection claim shapes correctly;
 * (2) load the real Consumer (by the consumer_id claim OAuth2ConsumerTokenCustomizer
 * stamps at issuance) so the resulting principal matches ApiKeyAuthenticationToken's
 * shape exactly.
 */
public class OAuth2ConsumerAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final ConsumerRepository consumerRepository;
    private final JwtGrantedAuthoritiesConverter scopeAuthoritiesConverter = new JwtGrantedAuthoritiesConverter();

    public OAuth2ConsumerAuthenticationConverter(ConsumerRepository consumerRepository) {
        this.consumerRepository = consumerRepository;
        this.scopeAuthoritiesConverter.setAuthorityPrefix("");
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        Collection<GrantedAuthority> authorities = scopeAuthoritiesConverter.convert(jwt);

        String consumerId = jwt.getClaimAsString("consumer_id");
        if (consumerId == null) {
            throw new BadCredentialsException("Token is missing the consumer_id claim");
        }

        Consumer consumer = consumerRepository.findById(UUID.fromString(consumerId))
                .orElseThrow(() -> new BadCredentialsException("Token references an unknown consumer: " + consumerId));

        return new OAuth2ConsumerAuthenticationToken(consumer, authorities);
    }
}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=OAuth2ConsumerAuthenticationConverterTest`
Expected: PASS (3 tests).

- [ ] **Step 6: Write `OAuth2ConsumerTokenCustomizer`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerTokenCustomizer.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;
import org.springframework.stereotype.Component;

/**
 * Spring Authorization Server auto-detects any OAuth2TokenCustomizer<JwtEncodingContext>
 * bean in context and applies it to every issued access token. RegisteredClient.getId()
 * is always the Consumer's own UUID (see JpaRegisteredClientRepository), so this needs
 * no database lookup — it just re-exposes an id Spring Authorization Server already
 * resolved as an explicit claim OAuth2ConsumerAuthenticationConverter can read later.
 */
@Component
public class OAuth2ConsumerTokenCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

    @Override
    public void customize(JwtEncodingContext context) {
        context.getClaims().claim("consumer_id", context.getRegisteredClient().getId());
    }
}
```

- [ ] **Step 7: Run the full suite to confirm nothing broke**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/creditscore/platform/identity/auth/OAuth2ConsumerAuthenticationToken.java \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerTokenCustomizer.java \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverter.java \
  src/test/java/com/creditscore/platform/identity/auth/oauth2/OAuth2ConsumerAuthenticationConverterTest.java
git commit -m "Add OAuth2 token claim customizer and resource-server authentication converter"
```

---

### Task 4: `JpaRegisteredClientRepository`

**Files:**
- Create: `src/main/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepository.java`
- Test: `src/test/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepositoryTest.java`

**Interfaces:**
- Consumes: `ConsumerRepository.findById`, `ConsumerRepository.findByOauthClientId` (Task 1), `Consumer.getOauthClientId/getOauthClientSecretHash/setOauthClientId/setOauthClientSecretHash` (Task 1).
- Produces: `JpaRegisteredClientRepository implements RegisteredClientRepository` — a `@Component`, autodetected by Spring Authorization Server in Task 5 (exactly one `RegisteredClientRepository` bean must exist in context). Every `RegisteredClient` this produces has `getId()` equal to the source `Consumer`'s UUID string — Task 3's `OAuth2ConsumerTokenCustomizer` depends on this.

- [ ] **Step 1: Write the failing tests**

Create `src/test/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepositoryTest.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.creditscore.platform.identity.consumer.ConsumerScope;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class JpaRegisteredClientRepositoryTest {

    private final ConsumerRepository consumerRepository = mock(ConsumerRepository.class);
    private final JpaRegisteredClientRepository repository = new JpaRegisteredClientRepository(consumerRepository);

    @Test
    void findByClientIdBuildsAClientCredentialsRegisteredClient() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = Consumer.forOAuth2Client("Acme Lender", "ops@acme.test", Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        consumer.setOauthClientId("client_abc");
        consumer.setOauthClientSecretHash("{bcrypt}hashed");
        when(consumerRepository.findByOauthClientId("client_abc")).thenReturn(Optional.of(consumer));

        RegisteredClient registeredClient = repository.findByClientId("client_abc");

        assertThat(registeredClient).isNotNull();
        assertThat(registeredClient.getId()).isEqualTo(consumerId.toString());
        assertThat(registeredClient.getClientId()).isEqualTo("client_abc");
        assertThat(registeredClient.getClientSecret()).isEqualTo("{bcrypt}hashed");
        assertThat(registeredClient.getScopes()).containsExactly("SCORE_READ");
        assertThat(registeredClient.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.CLIENT_CREDENTIALS);
    }

    @Test
    void findByClientIdReturnsNullWhenNotFound() {
        when(consumerRepository.findByOauthClientId("missing")).thenReturn(Optional.empty());

        assertThat(repository.findByClientId("missing")).isNull();
    }

    @Test
    void findByIdReturnsNullForApiKeyOnlyConsumer() {
        UUID consumerId = UUID.randomUUID();
        Consumer consumer = new Consumer("Legacy Consumer", null, "somehash", "csk_1234",
                Set.of(ConsumerScope.SCORE_READ));
        ReflectionTestUtils.setField(consumer, "id", consumerId);
        when(consumerRepository.findById(consumerId)).thenReturn(Optional.of(consumer));

        assertThat(repository.findById(consumerId.toString())).isNull();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw test -Dtest=JpaRegisteredClientRepositoryTest`
Expected: FAIL to compile — `JpaRegisteredClientRepository` doesn't exist yet.

- [ ] **Step 3: Write `JpaRegisteredClientRepository`**

Create `src/main/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepository.java`:

```java
package com.creditscore.platform.identity.auth.oauth2;

import com.creditscore.platform.identity.consumer.Consumer;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

/**
 * Adapts Consumer rows to RegisteredClient — there is no separate client table. A
 * Consumer provisioned with OAuth2 credentials (ConsumerProvisioningService) IS the
 * registered client, keyed by the oauth_client_id/oauth_client_secret_hash columns it
 * already carried unused. RegisteredClient.getId() is always the Consumer's own UUID,
 * which OAuth2ConsumerTokenCustomizer relies on to stamp the consumer_id claim without
 * a second lookup at token-issuance time.
 */
@Component
public class JpaRegisteredClientRepository implements RegisteredClientRepository {

    private final ConsumerRepository consumerRepository;

    public JpaRegisteredClientRepository(ConsumerRepository consumerRepository) {
        this.consumerRepository = consumerRepository;
    }

    @Override
    @Transactional
    public void save(RegisteredClient registeredClient) {
        Consumer consumer = consumerRepository.findById(UUID.fromString(registeredClient.getId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "No consumer for registered client id: " + registeredClient.getId()));
        consumer.setOauthClientId(registeredClient.getClientId());
        consumer.setOauthClientSecretHash(registeredClient.getClientSecret());
    }

    @Override
    public RegisteredClient findById(String id) {
        return consumerRepository.findById(UUID.fromString(id)).map(this::toRegisteredClient).orElse(null);
    }

    @Override
    public RegisteredClient findByClientId(String clientId) {
        return consumerRepository.findByOauthClientId(clientId).map(this::toRegisteredClient).orElse(null);
    }

    private RegisteredClient toRegisteredClient(Consumer consumer) {
        if (consumer.getOauthClientId() == null || consumer.getOauthClientSecretHash() == null) {
            return null;
        }
        return RegisteredClient.withId(consumer.getId().toString())
                .clientId(consumer.getOauthClientId())
                .clientSecret(consumer.getOauthClientSecretHash())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS)
                .scopes(scopes -> consumer.getScopes().forEach(scope -> scopes.add(scope.name())))
                .tokenSettings(TokenSettings.builder()
                        .accessTokenTimeToLive(Duration.ofHours(1))
                        .build())
                .build();
    }
}
```

`Optional.map` returns an empty `Optional` when the mapping function itself returns `null` (its documented behavior), so the `null` returned by `toRegisteredClient` for a non-OAuth2 `Consumer` correctly becomes `.orElse(null)` rather than an `Optional` wrapping `null`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw test -Dtest=JpaRegisteredClientRepositoryTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Run the full suite**

Run: `./mvnw test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepository.java \
  src/test/java/com/creditscore/platform/identity/auth/oauth2/JpaRegisteredClientRepositoryTest.java
git commit -m "Add JpaRegisteredClientRepository adapting Consumer rows to RegisteredClient"
```

---

### Task 5: Authorization Server + Resource Server wiring

**Files:**
- Modify: `src/main/java/com/creditscore/platform/config/SecurityConfig.java`

**Interfaces:**
- Consumes: `JpaRegisteredClientRepository` (Task 4, autodetected — no explicit wiring needed since it's the only `RegisteredClientRepository` bean), `OAuth2ConsumerTokenCustomizer` (Task 3, autodetected the same way), `OAuth2ConsumerAuthenticationConverter` (Task 3, explicitly wired into `apiFilterChain` below).
- Produces: `/oauth2/token` (issuance), `/oauth2/jwks` (public key set), and `apiFilterChain` now accepts `Authorization: Bearer <jwt>` in addition to `X-API-Key`. This is the task where OAuth2 auth becomes end-to-end functional for the first time — Task 6 is the first full manual verification.

- [ ] **Step 1: Replace `SecurityConfig` with the final wiring**

Replace the full contents of `src/main/java/com/creditscore/platform/config/SecurityConfig.java` with:

```java
package com.creditscore.platform.config;

import com.creditscore.platform.billing.UsageMeter;
import com.creditscore.platform.identity.auth.AdminTokenFilter;
import com.creditscore.platform.identity.auth.ApiKeyAuthFilter;
import com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter;
import com.creditscore.platform.identity.consumer.ConsumerRepository;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.authorization.config.annotation.web.configuration.OAuth2AuthorizationServerConfiguration;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.util.UUID;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws JOSEException {
        RSAKey rsaKey = new RSAKeyGenerator(2048)
                .keyID(UUID.randomUUID().toString())
                .generate();
        JWKSet jwkSet = new JWKSet(rsaKey);
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

    @Bean
    @Order(2)
    public SecurityFilterChain authorizationServerFilterChain(HttpSecurity http) throws Exception {
        OAuth2AuthorizationServerConfiguration.applyDefaultSecurity(http);
        http.csrf(csrf -> csrf.disable());
        return http.build();
    }

    @Bean
    @Order(3)
    public SecurityFilterChain apiFilterChain(HttpSecurity http, ConsumerRepository consumerRepository,
                                               PlatformTransactionManager transactionManager,
                                               UsageMeter usageMeter, JwtDecoder jwtDecoder) throws Exception {
        ApiKeyAuthFilter apiKeyAuthFilter =
                new ApiKeyAuthFilter(consumerRepository, new TransactionTemplate(transactionManager), usageMeter);

        http
                .securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .addFilterBefore(apiKeyAuthFilter, UsernamePasswordAuthenticationFilter.class)
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
    @Order(4)
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

Changes from Task 2's version: added `jwkSource`, `jwtDecoder`, `authorizationServerSettings` beans; added the `authorizationServerFilterChain` at `@Order(2)` (the slot deliberately left open in Task 2); `apiFilterChain` gains the `JwtDecoder jwtDecoder` parameter and the `.oauth2ResourceServer(...)` line. `adminFilterChain` and `publicFilterChain` are unchanged from Task 2.

- [ ] **Step 2: Compile**

Run: `./mvnw clean compile`
Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Run the full test suite**

Run: `./mvnw test`
Expected: PASS — every unit test from Tasks 1-4 still passes unchanged (none of them touch `SecurityConfig`).

- [ ] **Step 4: Manual end-to-end verification**

```bash
docker compose down -v
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run &
sleep 15

# Provision an OAuth2 client
PROVISION=$(curl -s -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"OAuth2 Smoke Test","contactEmail":"ops@acme.test","scopes":["SCORE_READ","TRANSACTION_READ"]}')
echo "$PROVISION"
CLIENT_ID=$(echo "$PROVISION" | grep -o '"clientId":"[^"]*"' | cut -d'"' -f4)
CLIENT_SECRET=$(echo "$PROVISION" | grep -o '"clientSecret":"[^"]*"' | cut -d'"' -f4)

# Get a token
TOKEN_RESPONSE=$(curl -s -u "$CLIENT_ID:$CLIENT_SECRET" \
  -d 'grant_type=client_credentials' \
  http://localhost:8080/oauth2/token)
echo "$TOKEN_RESPONSE"
ACCESS_TOKEN=$(echo "$TOKEN_RESPONSE" | grep -o '"access_token":"[^"]*"' | cut -d'"' -f4)

# Decode the payload to inspect claims
echo "$ACCESS_TOKEN" | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool

# Use the token against an existing endpoint the client has scope for
curl -i http://localhost:8080/api/v1/usage/summary -H "Authorization: Bearer $ACCESS_TOKEN"

# Use it against an endpoint the client lacks scope for (BUSINESS_WRITE) -> 403
curl -i http://localhost:8080/api/v1/businesses -H "Authorization: Bearer $ACCESS_TOKEN"

# Confirm the pre-existing API-key path still works unchanged
grep "Demo consumer API key" /dev/null 2>/dev/null || true
```

Expected: the decoded JWT payload contains `"scope": "SCORE_READ TRANSACTION_READ"` and `"consumer_id": "<the provisioned consumer's UUID>"`. `GET /api/v1/usage/summary` (scope-free) returns `200`. `GET /api/v1/businesses` (needs `BUSINESS_WRITE`, which this client wasn't granted) returns `403` with the existing `forbidden` JSON body. Stop the app afterward.

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/creditscore/platform/config/SecurityConfig.java
git commit -m "Wire Spring Authorization Server and OAuth2 resource-server support into SecurityConfig"
```

---

### Task 6: Final verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `ARCHITECTURE.md`
- Modify: `docker-compose.yml`
- Modify: `src/main/java/com/creditscore/platform/identity/auth/oauth2/package-info.java`

**Interfaces:**
- None — this task only verifies and documents what Tasks 1-5 built.

- [ ] **Step 1: Pass `PLATFORM_ADMIN_TOKEN` through to the containerized `app` service**

In `docker-compose.yml`, add `PLATFORM_ADMIN_TOKEN` to the `app` service's `environment` block (after `SPRING_PROFILES_ACTIVE: seed`):

```yaml
      PLATFORM_ADMIN_TOKEN: local-dev-admin-token
```

- [ ] **Step 2: Update `identity/auth/oauth2/package-info.java`**

Replace the contents of `src/main/java/com/creditscore/platform/identity/auth/oauth2/package-info.java`:

```java
/**
 * OAuth2 client-credentials support, coexisting with API-key auth (see
 * {@code com.creditscore.platform.identity.auth.ApiKeyAuthFilter}) rather than
 * replacing it. {@link com.creditscore.platform.identity.auth.oauth2.JpaRegisteredClientRepository}
 * adapts {@code Consumer} rows into Spring Authorization Server's {@code RegisteredClient}
 * — there is no separate client table. Token issuance stamps a {@code consumer_id}
 * claim ({@link com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerTokenCustomizer});
 * {@link com.creditscore.platform.identity.auth.oauth2.OAuth2ConsumerAuthenticationConverter}
 * reads it back on the resource-server side to build the same principal shape
 * {@code ApiKeyAuthenticationToken} uses. See
 * {@code docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md}.
 */
package com.creditscore.platform.identity.auth.oauth2;
```

- [ ] **Step 3: Update `README.md`**

In `README.md`, add a new subsection after the existing `### Endpoints` table (after the `Auth: X-API-Key...` line, before `## Tests`):

```markdown
### OAuth2 client-credentials (coexists with API keys)

Provision a client (requires `PLATFORM_ADMIN_TOKEN`, defaults to `local-dev-admin-token`
locally):

```bash
curl -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Acme Lender","contactEmail":"ops@acme.test","scopes":["SCORE_READ"]}'
```

Get a token (client secret is HTTP Basic, matching the `client_credentials` grant):

```bash
curl -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials' \
  http://localhost:8080/oauth2/token
```

Call any existing endpoint with `Authorization: Bearer <access_token>` instead of
`X-API-Key` — every scope-based check behaves identically either way. Access tokens
expire after 1 hour (no refresh tokens for this grant type — re-authenticate with the
client secret). Full design rationale:
`docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md`.
```

- [ ] **Step 4: Update `ARCHITECTURE.md`**

In `ARCHITECTURE.md`'s extension-points table, change the OAuth2 row from a stub description to reflect what's actually built:

```markdown
| OAuth2 client-credentials  | Built — `identity.auth.oauth2`; coexists with API-key auth, see README |
```

- [ ] **Step 5: Final full-suite run and fresh-container verification**

```bash
./mvnw test
docker compose down -v
docker compose --profile full up --build -d
sleep 30
curl -i http://localhost:8080/swagger-ui.html
curl -s -X POST http://localhost:8080/api/v1/admin/consumers \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"name":"Container Smoke Test","scopes":["SCORE_READ"]}'
docker compose --profile full down
```

Expected: `./mvnw test` passes in full. The containerized `app` service (built from the `Dockerfile`, picking up `PLATFORM_ADMIN_TOKEN` from `docker-compose.yml`) serves Swagger UI and successfully provisions a consumer, confirming the env var passthrough from Step 1 actually reaches the container.

- [ ] **Step 6: Commit**

```bash
git add README.md ARCHITECTURE.md docker-compose.yml \
  src/main/java/com/creditscore/platform/identity/auth/oauth2/package-info.java
git commit -m "Document OAuth2 client-credentials auth and pass PLATFORM_ADMIN_TOKEN into the container"
```

---

## Self-Review

**Spec coverage:** Every section of `docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md` maps to a task — coexistence (Tasks 2/5, both filter chains active), provisioning endpoint (Task 2), self-hosted Authorization Server (Task 5), schema change (Task 1), the scope-mapping + Consumer-principal converter (Task 3), `@Order` correctness for the admin/api chain overlap (Task 2), ephemeral RSA key (Task 5, documented as MVP limitation in the design spec already), manual curl verification (Task 5 Step 4, Task 6 Step 5), README/ARCHITECTURE updates (Task 6).

**Placeholder scan:** No `TBD`/`TODO`/"add appropriate handling" language anywhere in the tasks above — every step has complete, literal code or a literal shell command with expected output.

**Type/signature consistency across tasks:** `Consumer.forOAuth2Client(String, String, Set<ConsumerScope>)` (Task 1) is called identically in Task 2's `ConsumerProvisioningService`, Task 3's test, and Task 4's test. `ConsumerProvisioningService.ProvisionedConsumer(UUID, String, String)` field names (`consumerId`/`clientId`/`clientSecret`) match `ConsumerProvisionResponse`'s constructor call in `AdminConsumerController` (Task 2) and the curl walkthrough's `grep` patterns (Task 5/6). `OAuth2ConsumerAuthenticationConverter`'s constructor signature (`ConsumerRepository`) matches its instantiation in `SecurityConfig.apiFilterChain` (Task 5). The `consumer_id` claim name is identical in `OAuth2ConsumerTokenCustomizer` (writes it, Task 3) and `OAuth2ConsumerAuthenticationConverter` (reads it, Task 3) — verified as the same literal string in both. `RegisteredClient.withId(consumer.getId().toString())` (Task 4) is exactly what `context.getRegisteredClient().getId()` (Task 3's customizer) later reads back. `@Order(2)` is explicitly reserved unused in Task 2's `SecurityConfig` and filled by Task 5's `authorizationServerFilterChain` — confirmed both tasks' full-file listings agree on this.

No gaps found.
