# SME Credit-Scoring Platform

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

## Tests

```bash
./mvnw test
```

Covers: mock-adapter determinism (same `DataSource` → identical synthetic history) and
its since-filter/gap-month invariants, mobile-money → `Transaction` normalization
mapping, each rule-based scoring rule in isolation, and the OAuth2 auth layer —
`Consumer`'s OAuth2 factory/accessors, client provisioning and secret hashing, the JWT
claim customizer/converter's scope-mapping and consumer-lookup logic,
`JpaRegisteredClientRepository`'s `Consumer`-to-`RegisteredClient` adaptation, and
`AdminTokenFilter`/`OAuth2UsageMeteringFilter`'s request-level behavior.

## Full containerized run

```bash
docker compose --profile full up --build
```

## Local dev reset

To wipe local data and re-apply migrations from scratch (e.g. after a migration
change):

```bash
docker compose down -v
docker compose up -d postgres
```
