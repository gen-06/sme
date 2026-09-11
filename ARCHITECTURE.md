# Architecture

Single Maven module, package-per-concern. This is deliberate: the "monorepo-friendly"
requirement from the product brief is satisfied by strict, one-directional package
dependencies — not by standing up a multi-module Maven reactor for one deployable.
When a real split is needed later (e.g. `scoring-core`, `ingestion-adapters`, `api-service`
as independently deployable/versioned modules), it's a mechanical "move package, add a
`<module>`" operation, not a redesign, *because* these boundaries are already enforced.

## Package dependency rules

- `scoring` depends only on `normalization.Transaction` (plus its own types). It never
  imports `ingestion`, `api`, or `identity`. This is what makes "scoring stays
  market-agnostic and swappable for an ML model" true in the compiler, not just in a doc.
- `normalization` depends on `ingestion`'s contract types (`RawTransactionRecord`,
  `AdapterType`) one-directionally. `ingestion` never imports `normalization`.
- `ingestion.mobilemoney` (and the future `ecommerce` / `accounting` / `pos` packages)
  depend only on `ingestion`'s core interfaces (`DataAdapter`, `SyncContext`,
  `SyncResult`) — never on each other. Adding a market/source means adding one new
  adapter package; nothing else changes.
- `sync.DataSyncService` is the single orchestration point allowed to know about both
  `ingestion` and `normalization` (adapter → normalizer → persist). Controllers and the
  Spring Batch job call it; it doesn't call back into either.
- `identity.auth` exposes only `Authentication` / `@AuthenticationPrincipal` to the rest
  of the app. `api` controllers never reference API keys directly. That boundary is what
  let OAuth2 client-credentials support be added purely as a filter-chain change: not one
  controller was rewritten, and — because the boundary was an `Authentication`, not an
  API key — the two mechanisms ended up **coexisting permanently** rather than one
  replacing the other. That is the stronger outcome: no existing API-key consumer had to
  migrate, and both paths resolve to the same `Consumer` principal, so every scope check
  downstream is identical whichever credential a caller presents.

## Extension points

| Future capability          | Extension point                                             |
|-----------------------------|---------------------------------------------------------------|
| New data source markets    | New `ingestion.<source>` package implementing `DataAdapter`  |
| ML scoring                 | New `scoring.ml.MlScoringEngineV1` implementing `ScoringEngine`, flip `app.scoring.active-version` |
| OAuth2 client-credentials  | Built — `identity.auth.oauth2`; coexists with API-key auth, see README |
| Usage-based billing/pricing| `billing.UsageRecord` already captures raw usage; add pricing logic on top |
| Redis score caching        | `docker-compose.yml` has a commented-out `redis` service ready to enable |

Rows other than OAuth2 are stubs — a named seam, not an implementation.

## Known limitations

Accepted MVP trade-offs in the auth layer, each of which must be addressed before this
runs as more than a single production instance. README.md carries the operator-facing
version of this list.

| Limitation | Consequence | Blocks |
|------------|-------------|--------|
| RSA signing key generated in memory at every startup (`SecurityConfig.jwkSource`) | Every restart invalidates **all** outstanding access tokens immediately, not just at their 1-hour expiry, and rotates the published `/oauth2/jwks` key set | Multi-instance deployment — two instances would sign with different keys and reject each other's tokens |
| No token revocation endpoint | A non-`ACTIVE` `Consumer` cannot mint new tokens, but an already-issued token stays valid until it expires | Prompt credential compromise response |
| No client-secret rotation flow | The secret is shown once at provisioning; replacing it means provisioning a new consumer | Zero-downtime credential rotation |
| Spring Authorization Server's default in-memory `OAuth2AuthorizationService` retains one entry per issued token, with no eviction | Heap grows with every token issued until restart | A long-lived single instance, and any multi-instance deployment |

The first and last rows bound each other today: the in-memory authorization store only
grows until the next restart, and that same restart is what invalidates every key.
Persisting one without the other would be a partial fix — a persisted
`OAuth2AuthorizationService` is only useful alongside a persisted signing key.

## Scoring model versioning

`ScoreProfile` rows are append-only — there is no update path. Each row is stamped with
the `modelVersion` of whichever `ScoringEngine` produced it
(`ScoringEngine.getModelVersion()`). `ScoringEngineRegistry` collects all `ScoringEngine`
beans keyed by version; `app.scoring.active-version` in `application.yml` selects which
engine computes *new* scores. Historical rows are never recomputed or migrated when the
active version changes — that's the mechanism behind "scoring models can evolve without
breaking historical scores."
