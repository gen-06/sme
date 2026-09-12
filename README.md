# SME Credit-Scoring Platform

[![CI](https://github.com/gen-06/sme/actions/workflows/ci.yml/badge.svg)](https://github.com/gen-06/sme/actions/workflows/ci.yml)

Global SME credit-scoring infrastructure: aggregates alternative data (mobile money,
e-commerce, accounting software, POS) for small/medium businesses in emerging markets
and turns it into a standardized creditworthiness signal, served to lenders/fintechs
via a B2B REST API. See `ARCHITECTURE.md` for package boundaries and extension points.

This is the MVP slice: one data source (a realistic mock mobile-money adapter),
normalization, a rule-based scoring engine, and the full REST surface, authenticated by
API key or OAuth2 client credentials. Everything else in the product brief (more
adapters, ML scoring, billing, dashboards) has an explicit, documented extension point
already in place. See [Known limitations](#known-limitations) for what the auth layer
does *not* do yet.

## Requirements

- Java 21
- Docker + Docker Compose
- No local Maven install needed — use the bundled `./mvnw` wrapper

## Run it

```bash
docker compose up -d postgres

# Seed data provisions the demo API-key consumer (and the demo businesses/scores), so
# without the seed profile there is no API key to call anything with. Additional
# consumers can be provisioned at runtime through POST /api/v1/admin/consumers, but
# that endpoint issues OAuth2 client credentials only — it does not mint API keys.
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run
```

The `seed` profile is also what allows `app.admin.platform-admin-token` to keep its
published local-dev default. Outside that profile the application **refuses to start**
unless `PLATFORM_ADMIN_TOKEN` is set to a real secret — the default is printed in this
file and in `docker-compose.yml`, so a deployment that silently kept it would leave the
admin endpoint open to anyone who has read them.

On startup, the log prints a demo API key (shown once) and three business IDs — two
pre-synced with a computed score (Kenya, Nigeria), one left unsynced (Ghana) to
demonstrate the "no score yet" state:

```
Demo consumer API key (shown once, not recoverable): csk_...
Kenya business id (synced, has score):   ...
Nigeria business id (synced, has score): ...
Ghana business id (unsynced, no score):  ...
```

## Try the API

```bash
API_KEY=csk_... ./scripts/demo.sh
```

or import `postman/credit-scoring-platform.postman_collection.json` +
`postman/local.postman_environment.json`, set the `apiKey` variable to the key from
the log, and run the collection top to bottom.

Swagger UI (no auth required): http://localhost:8080/swagger-ui.html

### Endpoints

| Method | Path                                      | Scope required     |
|--------|--------------------------------------------|---------------------|
| POST   | `/api/v1/businesses`                        | `BUSINESS_WRITE`     |
| GET    | `/api/v1/businesses`                        | `BUSINESS_WRITE`     |
| GET    | `/api/v1/businesses/{id}`                   | `BUSINESS_WRITE`     |
| POST   | `/api/v1/businesses/{id}/data-sources`      | `DATA_SOURCE_WRITE`  |
| POST   | `/api/v1/businesses/{id}/sync`              | `SYNC_TRIGGER`       |
| GET    | `/api/v1/businesses/{id}/score`             | `SCORE_READ`         |
| GET    | `/api/v1/businesses/{id}/score/history`     | `SCORE_READ`         |
| GET    | `/api/v1/businesses/{id}/transactions`      | `TRANSACTION_READ`   |
| GET    | `/api/v1/usage/summary`                     | (none — any authenticated consumer) |

Auth: `X-API-Key: <key>` header, or `Authorization: Bearer <access_token>` — see
[OAuth2 client-credentials](#oauth2-client-credentials-coexists-with-api-keys) below.
The seed consumer holds all scopes.

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
curl -u "$CLIENT_ID:$CLIENT_SECRET" -d 'grant_type=client_credentials&scope=SCORE_READ' \
  http://localhost:8080/oauth2/token
```

Suspend or revoke a consumer (also requires `PLATFORM_ADMIN_TOKEN`):

```bash
curl -X PATCH http://localhost:8080/api/v1/admin/consumers/$CONSUMER_ID/status \
  -H 'Content-Type: application/json' \
  -H 'X-Platform-Admin-Token: local-dev-admin-token' \
  -d '{"status":"SUSPENDED"}'
```

This kills every one of that consumer's outstanding access tokens immediately, not
just future issuance — see [Known limitations](#known-limitations) for exactly what
that does and doesn't cover. `SUSPENDED` can be reversed (`{"status":"ACTIVE"}`);
`REVOKED` is terminal — no further transition is accepted once a consumer is revoked.

Rotate a client secret without downtime:

```bash
curl -X POST http://localhost:8080/api/v1/admin/consumers/$CONSUMER_ID/rotate-secret \
  -H 'X-Platform-Admin-Token: local-dev-admin-token'
```

Returns a new secret (shown once, same as provisioning). The old secret keeps working
for `app.oauth2.secret-rotation-grace-period-hours` (default 24h) so deployed clients
can update on their own schedule — see [Known limitations](#known-limitations) for the
two-generations-only caveat.

Call any existing endpoint with `Authorization: Bearer <access_token>` instead of
`X-API-Key` — every scope-based check behaves identically either way. Access tokens
expire after 1 hour by default (tunable via `app.oauth2.access-token-ttl-minutes`); there
are no refresh tokens for this grant type, so re-authenticate with the client secret.
Full design rationale:
`docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md`.

Only three Authorization Server endpoints are exposed: `/oauth2/token`, `/oauth2/jwks`
and `/.well-known/oauth-authorization-server`. The rest of Spring Authorization Server's
surface (`/oauth2/authorize`, `/oauth2/revoke`, `/oauth2/introspect`, the device-grant
endpoints) is deliberately closed off, because this platform implements only the
client-credentials grant.

## Known limitations

These are accepted MVP trade-offs, not oversights.

- **No per-token revocation.** Suspending or revoking a `Consumer`
  (`PATCH /api/v1/admin/consumers/{id}/status`) blocks new token issuance
  (`JpaRegisteredClientRepository` returns `null` for a non-`ACTIVE` consumer) *and*
  immediately invalidates every one of that consumer's already-issued tokens
  (`OAuth2ConsumerAuthenticationConverter` re-checks status on every request). What
  this does not provide is standards-compliant single-token revocation (RFC 7009):
  there is no `/oauth2/revoke` endpoint, and no way to kill one token while leaving a
  consumer's other outstanding tokens valid — the only granularity is the whole
  consumer. This guarantee also only holds for requests this app itself authenticates:
  if a future caller validates these JWTs independently using only `/oauth2/jwks` (a
  gateway, a sidecar, another service), the signature is still valid and nothing there
  consults `consumers.status` — the token would still work there until it expires.
- **Client-secret rotation keeps only two generations, no more.** `POST
  /api/v1/admin/consumers/{id}/rotate-secret` issues a new secret and keeps the old one
  working for a grace period (`app.oauth2.secret-rotation-grace-period-hours`, default
  24h) — deployed clients update on their own schedule without downtime. Rotating again
  before that grace period ends immediately drops the older secret rather than keeping
  three generations valid; if you need to rotate twice in quick succession, the first
  rotation's grace window gets cut short.
- **No signing-key rotation.** The RSA key is persisted (`oauth2_signing_keys`,
  generated once and reused indefinitely across restarts and instances — see
  `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md`), which solved the
  "every restart invalidates every token" and "two instances reject each other's
  tokens" problems. There is still no mechanism to rotate to a new key while
  continuing to accept tokens signed by an old one: replacing the stored key today
  invalidates every outstanding token at once, the same way a restart used to.
- **The signing key and issued tokens are stored in plaintext in Postgres.** Database
  read access to `oauth2_signing_keys` is equivalent to being able to forge a valid
  access token for any consumer indefinitely (the row holds the full RSA key, including
  its private parameters). `oauth2_authorization.access_token_value` holds live,
  unexpired bearer tokens in plaintext — this is Spring Authorization Server's own
  default schema shape, not something this plan added. Treat database access controls
  and backup encryption as part of this system's security boundary, not an afterthought.
- **A corrupt or unparseable signing-key row is a total outage until fixed manually.**
  If the single row in `oauth2_signing_keys` ever becomes unparseable, every instance
  fails to start (fail-fast, by design — silent regeneration would be worse). Recovery:
  delete the row and restart; this invalidates every outstanding token, the same as a
  restart used to before this key was persisted.
- **Usage is metered per credential, not deduplicated across them.** A request that
  presents both `X-API-Key` and a `Bearer` token is recorded by both metering paths. No
  product decision has been made about which credential should win for billing.
- **Actuator exposure and the security matcher are two separate files with nothing
  enforcing agreement.** `publicFilterChain`'s `securityMatcher` in `SecurityConfig.java`
  lists `/actuator` and `/actuator/health` because those are the only paths
  `management.endpoints.web.exposure.include` (in `application.yml`) currently exposes.
  Adding another id to that `include` list (e.g. `metrics`) makes `/actuator/metrics`
  live without any filter chain claiming it — this app has no final catch-all chain, so
  an unmatched path bypasses Spring Security entirely rather than falling through to a
  default-deny (confirmed live: `/actuator` itself served real content, fully
  unauthenticated, before it was added to a matcher). Nothing currently tests that these
  two files stay in sync.

## Tests

```bash
./mvnw test
```

Covers: mock-adapter determinism (same `DataSource` → identical synthetic history) and
its since-filter/gap-month invariants, mobile-money → `Transaction` normalization
mapping, each rule-based scoring rule in isolation, and the OAuth2 auth layer —
`Consumer`'s OAuth2 factory/accessors, client provisioning and secret hashing, the JWT
claim customizer/converter's scope-mapping, consumer-lookup and status-enforcement
logic, `JpaRegisteredClientRepository`'s `Consumer`-to-`RegisteredClient` adaptation,
`OAuth2SigningKeyService`'s get-or-create/parse logic, `Consumer`'s status-transition
rules (including `REVOKED` being terminal) and secret-rotation grace-period logic,
`RotatingClientSecretPasswordEncoder`'s composite-secret matching, and
`AdminTokenFilter`/`OAuth2UsageMeteringFilter`'s request-level behavior.

## Full containerized run

```bash
docker compose --profile full up --build
```

The `app` service has a real Docker healthcheck against `GET /actuator/health` (checks
DB connectivity, returns `503`/`{"status":"DOWN"}` if Postgres is unreachable) —
`docker ps` reports `healthy`/`unhealthy` accordingly. This is the only actuator
endpoint exposed (`management.endpoints.web.exposure.include: health` in
`application.yml`); it returns no component detail (`show-details: never`) since it's
fully unauthenticated, same as Swagger UI.

## Local dev reset

To wipe local data and re-apply migrations from scratch (e.g. after a migration
change):

```bash
docker compose down -v
docker compose up -d postgres
```
