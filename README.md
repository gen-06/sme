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
| POST   | `/api/v1/businesses/{id}/data-sources`      | `DATA_SOURCE_WRITE`  |
| POST   | `/api/v1/businesses/{id}/sync`              | `SYNC_TRIGGER`       |
| GET    | `/api/v1/businesses/{id}/score`             | `SCORE_READ`         |
| GET    | `/api/v1/businesses/{id}/score/history`     | `SCORE_READ`         |
| GET    | `/api/v1/businesses/{id}/transactions`      | `TRANSACTION_READ`   |

Auth: `X-API-Key: <key>` header. The seed consumer holds all scopes.

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
