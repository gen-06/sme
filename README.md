# SME Credit-Scoring Platform

Global SME credit-scoring infrastructure: aggregates alternative data (mobile money,
e-commerce, accounting software, POS) for small/medium businesses in emerging markets
and turns it into a standardized creditworthiness signal, served to lenders/fintechs
via a B2B REST API. See `ARCHITECTURE.md` for package boundaries and extension points.

This is the MVP slice: one data source (a realistic mock mobile-money adapter),
normalization, a rule-based scoring engine, and the full API-key-authenticated REST
surface. Everything else in the product brief (more adapters, ML scoring, OAuth2,
billing, dashboards) has an explicit, documented extension point already in place.

## Requirements

- Java 21
- Docker + Docker Compose
- No local Maven install needed — use the bundled `./mvnw` wrapper

## Run it

```bash
docker compose up -d postgres

# Seed data is required locally: Consumer/API-key provisioning is seed-only in this
# pass (no admin endpoint yet), so without the seed profile every endpoint 401s.
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run
```

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

Auth: `X-API-Key: <key>` header. The seed consumer holds all scopes.

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
expire after 1 hour (no refresh tokens for this grant type — re-authenticate with the
client secret). Full design rationale:
`docs/superpowers/specs/2026-09-11-oauth2-client-credentials-design.md`.

## Tests

```bash
./mvnw test
```

Covers: mock-adapter determinism (same `DataSource` → identical synthetic history) and
its since-filter/gap-month invariants, mobile-money → `Transaction` normalization
mapping, and each rule-based scoring rule in isolation.

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
