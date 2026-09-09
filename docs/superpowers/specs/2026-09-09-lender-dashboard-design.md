# Lender Dashboard — Design Spec

## Context

The backend MVP (this repo) is complete and verified end to end: business
registration, mobile-money ingestion, normalization, rule-based scoring, and a
6-endpoint API-key-authenticated REST API. The product brief's "Frontend / UI
requirements" section calls for a separate Next.js + Tailwind lender-facing
dashboard consuming that API, with a clean fintech aesthetic (Stripe/Mercury-like)
and four core screens: consumer login, business search/lookup, an individual
business's score profile, and an API usage/billing overview.

This spec covers that dashboard. It lives in its own repo, sibling to this one,
so the two can deploy and version independently — the backend stays framework-
agnostic about who consumes it.

## Backend additions required

Two of the four screens have no backend data source today. Both are small,
additive endpoints reusing existing infrastructure — not new subsystems.

1. **`GET /api/v1/businesses?search=&page=&size=`** — paginated list of
   businesses, optional case-insensitive name search. Reuses the existing
   `BUSINESS_WRITE` scope (same scope the single-business lookup already uses).
   Needed for the business search/lookup screen; today only
   `GET /businesses/{id}` exists.
2. **`GET /api/v1/usage/summary`** — for the *authenticated* consumer only
   (derived from `Authentication` → `Consumer.id`, never a path parameter):
   total call count, a breakdown by endpoint, and the most recent N
   `UsageRecord` rows. `UsageRecord` rows are already written on every
   authenticated call (`JpaUsageMeter`); nothing reads them back today. No new
   scope — a consumer reading their own usage doesn't need a new permission
   concept, so this endpoint is available to any authenticated consumer.

Both endpoints are read-only, additive, and don't change any existing
endpoint's behavior or shape.

## Dashboard architecture

**Server-first**: the API key entered at "login" is stored in an `httpOnly`,
`Secure`, `SameSite=Lax` cookie via a Server Action. Server Components read
that cookie (via `next/headers`) and fetch the Java API directly — server to
server, so no CORS configuration is needed on the backend at all. Mutations
(register business, add data source, trigger sync) are Server Actions for the
same reason: they run on the Next.js server, read the cookie, call the Java
API, and the API key never reaches browser JavaScript.

Rejected alternatives:
- **Client-side fetch + localStorage** — would require adding CORS to
  `SecurityConfig` (a backend change with no other justification) and exposes
  the API key to any JS running on the page (XSS blast radius).
- **Full BFF proxy via Route Handlers** — more plumbing than needed; nothing
  in the current screens needs client-side polling or streaming. Can be added
  later for a specific feature without restructuring the rest.

A thin `lib/api-client.ts` wraps `fetch` with the base URL
(`JAVA_API_BASE_URL` env var) and the cookie-derived API key, and maps
non-2xx responses to a typed `ApiError`. Every server component/action goes
through this client — no raw `fetch` calls scattered around.

## Screens

1. **Login** (`/login`) — single API key input, a Server Action validates it
   by calling `GET /api/v1/usage/summary` (any authenticated call would do;
   this one doubles as "does this key work") and sets the cookie on success.
   Invalid key → inline error, no cookie set.
2. **Business search/lookup** (`/businesses`) — search input (debounced,
   client component) driving the new list endpoint; results as a table/card
   list with name, country, industry, status; empty state ("no businesses
   match") and loading skeleton states.
3. **Business score profile** (`/businesses/[id]`) — latest score, confidence
   badge, factor breakdown (bar chart, one bar per rule with its
   weighted contribution), score-over-time line chart from `/score/history`,
   and a paginated transactions table. Handles the "no score yet" state
   (Ghana-style unsynced business) distinctly from a real 404 (unknown ID).
4. **API usage overview** (`/usage`) — call volume by endpoint (bar chart) and
   a recent-calls table from the new summary endpoint. Explicitly labeled as
   raw usage, not billing — no pricing/invoicing exists in the backend yet
   (documented as deferred in `ARCHITECTURE.md`), so this screen doesn't
   pretend otherwise.

Every data view (search results, score profile, usage) gets its own explicit
empty/loading/error state per the brief's requirement — no bare spinners or
blank screens.

## Design system

- Next.js 15 App Router, TypeScript, Tailwind CSS.
- shadcn/ui as the component base, but with the default theme's spacing
  scale, border radius, and type scale overridden in `tailwind.config` /
  `globals.css` before any component is generated — per the brief, it should
  not read as an out-of-the-box shadcn template.
- Accent: deep blue (exact shade chosen during implementation against a
  neutral gray/white base — one accent, not a palette of button colors).
- Charts: Recharts — score trend as a line chart, factor breakdown as a bar
  chart. Chart components are the only client components in the score-profile
  route; data is fetched server-side and passed down as props.
- Typography: a distinct type pairing via `next/font`, not the Geist default,
  to reinforce "not a template."

## Testing / verification

- `npm run build` must pass (type checks + lint via Next.js's build-time
  checks).
- Manual verification against the running Java backend (`docker compose up -d
  postgres` + `SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run` in this
  repo) for all four screens: login with the seed key, search for a seeded
  business, view a synced business's score profile (Kenya/Nigeria) and an
  unsynced one (Ghana), view the usage overview after making a few API calls.
- No automated frontend test suite for this first pass (matches the backend
  MVP's own scope discipline — ship the working slice, add test coverage as
  the app grows past a handful of screens).

## Out of scope for this pass

OAuth2/session-based human login (backend doesn't support it yet — API-key
entry is the login for now), billing/pricing UI (no pricing logic exists
server-side), admin/ops views, Redis-backed caching, deployment to Vercel
(local dev only per this round).
