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
  of the app. `api` controllers never reference API keys directly — this is what makes
  the API-key → OAuth2 client-credentials swap a filter-chain change, not a controller
  rewrite.

## Extension points already in place (stubbed, not built, this pass)

| Future capability          | Extension point                                             |
|-----------------------------|---------------------------------------------------------------|
| New data source markets    | New `ingestion.<source>` package implementing `DataAdapter`  |
| ML scoring                 | New `scoring.ml.MlScoringEngineV1` implementing `ScoringEngine`, flip `app.scoring.active-version` |
| OAuth2 client-credentials  | Built — `identity.auth.oauth2`; coexists with API-key auth, see README |
| Usage-based billing/pricing| `billing.UsageRecord` already captures raw usage; add pricing logic on top |
| Redis score caching        | `docker-compose.yml` has a commented-out `redis` service ready to enable |

## Scoring model versioning

`ScoreProfile` rows are append-only — there is no update path. Each row is stamped with
the `modelVersion` of whichever `ScoringEngine` produced it
(`ScoringEngine.getModelVersion()`). `ScoringEngineRegistry` collects all `ScoringEngine`
beans keyed by version; `app.scoring.active-version` in `application.yml` selects which
engine computes *new* scores. Historical rows are never recomputed or migrated when the
active version changes — that's the mechanism behind "scoring models can evolve without
breaking historical scores."
