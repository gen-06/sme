# OAuth2 Client-Credentials Auth — Design Spec

## Context

The backend MVP ships with API-key auth only: a consumer presents `X-API-Key`,
`ApiKeyAuthFilter` hashes and looks it up, and `ApiKeyAuthenticationToken` carries
the resolved `Consumer` as the request's principal. This was always the documented
first step — `ARCHITECTURE.md`'s extension-points table names the exact swap point
("Replace `ApiKeyAuthFilter` in `SecurityConfig`"), and `Consumer` already carries
unused `oauthClientId`/`oauthClientSecretHash` columns reserved for this.

Two things make this urgent now rather than aspirational: there is currently no way
to onboard a real lender at all (Consumer creation is seed-data-only), and API keys
are a weaker credential than OAuth2 client-credentials for B2B integrations (long-lived
bearer secrets vs. short-lived signed tokens issued from a client secret that's used
only at token-request time, never on every call).

This spec covers replacing neither the auth model nor the existing endpoints' shapes —
it adds a second, stronger authentication path that converges on the exact same
`Consumer` principal and `ConsumerScope`-based authorization every existing controller
already uses, plus the provisioning endpoint needed to actually create OAuth2 clients.

## Decisions made before design (confirmed with the user)

- **Coexist, not replace.** Both `X-API-Key` and `Authorization: Bearer <jwt>` remain
  valid on `/api/**` simultaneously. API-key auth is not removed or deprecated by this
  work — that's a future decision once real consumers have migrated.
- **Provisioning is in scope.** A protected endpoint creates a `Consumer` and issues
  OAuth2 client credentials — without this, OAuth2 auth has no way to onboard anyone.
- **This app is its own token issuer.** Spring Authorization Server runs inside this
  same Spring Boot app rather than delegating to an external IdP (Auth0/Okta/Cognito).
  Keeps client lifecycle management in one codebase, matching the project's existing
  single-deployable architecture; an external IdP is a viable later swap if this
  platform ever needs enterprise SSO, but adds an external dependency and a second
  admin surface for no MVP benefit today.

## Architecture

**Dependencies added:** `spring-security-oauth2-authorization-server`,
`spring-security-oauth2-resource-server`.

**Token issuance** (new): Spring Authorization Server auto-configures `/oauth2/token`
when its filter chain is registered. A `RegisteredClientRepository` bean — implemented
as `JpaRegisteredClientRepository` in `identity.auth.oauth2` — adapts `Consumer` rows
into `RegisteredClient` objects at lookup time (by `oauthClientId`), rather than
maintaining a second client table disconnected from the existing `Consumer`/
`ConsumerScope` model. Granted scopes on the issued token come directly from
`Consumer.getScopes()`.

**Token validation** (extends `SecurityConfig.apiFilterChain`): add
`.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(...)))`
alongside the existing `.addFilterBefore(apiKeyAuthFilter, ...)`. A request
authenticates via whichever credential it presents — `ApiKeyAuthFilter` no-ops when
`X-API-Key` is absent (already true today), and the resource-server support no-ops
when there's no `Authorization: Bearer` header, so the two mechanisms don't conflict
in one filter chain.

**The one non-obvious piece that makes coexistence clean**: a custom
`JwtAuthenticationConverter` does two things instead of Spring's default behavior —

1. Maps the JWT's `scope` claim to bare authority strings (`BUSINESS_WRITE`, not
   Spring's default `SCOPE_BUSINESS_WRITE` prefix), so every existing
   `@PreAuthorize("hasAuthority('BUSINESS_WRITE')")` check works identically whether
   the request came in via API key or OAuth2 — zero controller changes anywhere.
2. Loads the real `Consumer` entity by the token's `client_id` claim (via
   `ConsumerRepository.findByOauthClientId`, a new derived query) and builds an
   authentication token whose principal is that `Consumer` — mirroring
   `ApiKeyAuthenticationToken`'s existing shape. This is what keeps
   `@AuthenticationPrincipal Consumer consumer` (already used in `UsageController`,
   and by any future controller) working unchanged regardless of auth method — the
   principal type never diverges.

**Signing key**: an RSA keypair generated at application startup (a `@Bean` producing
a `JWKSource<SecurityContext>` from an in-memory `RSAKey`), documented as an MVP
limitation — see below.

## Schema changes

One migration, `V8__oauth2_client_support.sql`:

- `ALTER TABLE consumers ALTER COLUMN api_key_hash DROP NOT NULL` — an OAuth2-only
  consumer provisioned by the new endpoint won't have an API key. Existing rows are
  unaffected (all currently have a non-null value); Postgres's `UNIQUE` constraint on
  `api_key_hash` already tolerates multiple `NULL`s under standard SQL null semantics,
  so no index change is needed.

No other schema change. `oauthClientId`/`oauthClientSecretHash` already exist on
`consumers` (unused until now); no new table for `RegisteredClient` data — the JPA
adapter reads/writes those two columns directly.

## Provisioning: `POST /api/v1/admin/consumers`

Request: `{ name, contactEmail?, scopes: ConsumerScope[] }`. Response (200, shown once):
`{ consumerId, clientId, clientSecret }` — `clientSecret` is generated, hashed via
Spring Security's `PasswordEncoder` (delegating encoder, e.g. bcrypt) before storage,
and never recoverable after this response, matching the existing API-key
generate-once/show-once pattern (`ApiKeyGenerator`).

**Guarding this endpoint**: rather than extending `ConsumerScope` with an `ADMIN`
value (which would mean a migration to widen the `consumer_scopes.scope` CHECK
constraint *and* seeding an internal admin consumer just to protect one route), this
endpoint is guarded by a dedicated `PLATFORM_ADMIN_TOKEN` environment variable, checked
via a small filter (`AdminTokenFilter`, constant-time comparison) scoped only to
`/api/v1/admin/**`. This keeps the existing scope model exactly as-is (every current
scope stays a real API-access permission, not overloaded with a meta-permission), is
trivially rotated by an operator without a database write, and matches how the demo
API key is already just logged at startup rather than management-console-issued — the
project's existing low-ceremony pattern for "the person running this owns the
credential," appropriate for a platform-operator action like provisioning a new B2B
client.

`AdminTokenFilter` sits in `identity.auth`, added via its own `SecurityFilterChain`
(a third one in `SecurityConfig`, matching `apiFilterChain`/`publicFilterChain`'s
existing pattern) matched to `/api/v1/admin/**`. This is a correctness-critical
ordering detail worth flagging explicitly: unlike the two existing chains (whose
`/api/**` and `/swagger-ui/**` matchers never overlap), `/api/v1/admin/**` is a
*subset* of `/api/**`, so Spring Security must be told to try the admin chain first —
via explicit `@Order` (lower value = higher priority) on the admin chain's `@Bean`
method, ordered ahead of `apiFilterChain`. Without this, request routing between the
two chains is undefined/incorrect. No existing chain in this codebase has needed
`@Order` before now, since the two current matchers are disjoint.

## Verification against the existing test/deploy story

- No `@SpringBootTest`/`@DataJpaTest` exists anywhere in this repo (an established,
  deliberate pattern — see `README.md`'s Tests section); this work follows the same
  convention. New pure-unit coverage: `JwtAuthenticationConverter`'s scope-mapping
  logic (given a JWT with a `scope` claim, assert the resulting authorities have no
  `SCOPE_` prefix) can be tested without a Spring context, matching the style of the
  existing `RuleBasedScoringEngineV1` rule tests.
- End-to-end manual verification (curl): call `POST /api/v1/admin/consumers` with the
  admin token, get back `clientId`/`clientSecret`; call `POST /oauth2/token` with
  `grant_type=client_credentials` and HTTP Basic auth using those credentials, get a
  JWT; call an existing endpoint (e.g. `GET /api/v1/businesses`) with
  `Authorization: Bearer <jwt>` and confirm it succeeds exactly like the API-key path
  does today; confirm a token whose consumer lacks a given scope gets 403 on an
  endpoint requiring it, identically to the existing API-key 403 behavior.

## Known MVP limitations (documented, not solved by this pass)

- **Ephemeral signing key.** The RSA keypair is generated fresh on every application
  startup. Fine for a single instance (tokens issued before a restart become
  unverifiable after it, but they're short-lived — see below), wrong for a multi-instance
  or rolling-deploy setup. A real production deploy needs a persisted, rotatable key
  (e.g. loaded from a secret store) — out of scope for this pass, noted as the next
  hardening step in `ARCHITECTURE.md`.
- **No token revocation before expiry.** A stateless JWT can't be invalidated
  server-side once issued (no `OAuth2AuthorizationService` persistence in this MVP —
  using Spring Authorization Server's default in-memory service is sufficient since we
  don't need to track/revoke). Mitigated by a short access-token TTL (recommend 1 hour,
  configurable via `app.oauth2.access-token-ttl-minutes`, which is applied through the
  `RegisteredClient`'s `TokenSettings` — not `AuthorizationServerSettings`, which only
  carries issuer/endpoint URIs). A suspended `Consumer`
  (`ConsumerStatus.SUSPENDED`) stops being able to mint *new* tokens immediately, but an
  already-issued token already in a caller's hands stays valid until it naturally
  expires. Acceptable for MVP; a future pass could add per-request status re-checks
  (trading the stateless-JWT performance benefit for that guarantee) if it matters
  before revocation infrastructure is built.
- **No client-secret rotation flow.** Provisioning issues one secret; there's no
  "rotate my secret" endpoint yet. A consumer that loses its secret needs a new
  `POST /api/v1/admin/consumers` call (effectively a new client) until a rotation
  endpoint is built.

## Out of scope for this pass

Removing or deprecating API-key auth (a future decision once consumers have
migrated), self-service consumer signup (provisioning stays operator-driven via the
admin token), refresh tokens (client-credentials grant doesn't use them — each caller
re-authenticates with its client secret when its token expires, which is the standard,
correct pattern for this grant type), a UI for consumer/client management (the
dashboard's screens are lender-facing, not platform-admin-facing), and per-request
`ConsumerStatus` re-validation for already-issued OAuth2 tokens (see limitations above).
