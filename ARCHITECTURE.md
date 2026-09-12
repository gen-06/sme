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

Accepted MVP trade-offs in the auth layer. README.md carries the operator-facing
version of this list.

| Limitation | Consequence | Blocks |
|------------|-------------|--------|
| No per-token revocation (RFC 7009) | `PATCH /api/v1/admin/consumers/{id}/status` blocks new issuance and kills every outstanding token for that consumer at once (`OAuth2ConsumerAuthenticationConverter` re-checks status per request); there is no way to revoke one token while leaving a consumer's others valid | Fine-grained, per-token credential compromise response |
| No client-secret rotation flow | The secret is shown once at provisioning; replacing it means provisioning a new consumer | Zero-downtime credential rotation |
| No signing-key rotation | The RSA key (persisted in `oauth2_signing_keys`) is reused indefinitely; replacing it invalidates every outstanding token at once | Zero-downtime key rotation |
| Signing key and issued tokens stored in plaintext in Postgres | DB read access is equivalent to forging tokens for any consumer (the key row includes private parameters); issued tokens are also stored unencrypted (SAS's own default schema) | Treating the database as outside the security boundary |
| A corrupt/unparseable signing-key row fails every instance's startup | Total outage until an operator deletes the row and restarts (fail-fast by design) | Zero-touch recovery from key-row corruption |

The signing key and the OAuth2 authorization store are both persisted in Postgres as
of `docs/superpowers/specs/2026-09-11-oauth2-persistence-design.md` — this platform no
longer has a row in this table for either "invalidates every token on restart" or
"grows without bound," and the auth layer itself no longer blocks multi-instance
deployment. `SyncJobScheduler`'s reconciliation job is also safe for multiple
instances: `@SchedulerLock` (ShedLock, backed by the `shedlock` table, V12) ensures
only one instance runs a given cron tick, so multi-instance deployment no longer means
redundant per-instance reconciliation passes — provided a run completes within the
lock's 30-minute lease (`lockAtMostFor`). A reconciliation pass that runs longer than
that (plausible with a real, non-mock adapter making external API calls across the
full catalog, not the current sub-second mock) has its lock expire while still
running, and a second instance's next tick can then start a concurrent pass — the
same double-execution this fix otherwise closes.

## Scoring model versioning

`ScoreProfile` rows are append-only — there is no update path. Each row is stamped with
the `modelVersion` of whichever `ScoringEngine` produced it
(`ScoringEngine.getModelVersion()`). `ScoringEngineRegistry` collects all `ScoringEngine`
beans keyed by version; `app.scoring.active-version` in `application.yml` selects which
engine computes *new* scores. Historical rows are never recomputed or migrated when the
active version changes — that's the mechanism behind "scoring models can evolve without
breaking historical scores."
