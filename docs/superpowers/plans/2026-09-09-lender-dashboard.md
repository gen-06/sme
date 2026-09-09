# Lender Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship a lender-facing Next.js dashboard (separate repo) with four screens — login, business search/lookup, business score profile, API usage overview — consuming the existing Java credit-scoring API, plus the two small backend endpoints those screens need.

**Architecture:** Server-first Next.js App Router app. An API key entered at login is stored in an `httpOnly` cookie; Server Components read it and fetch the Java API directly (server-to-server, no CORS needed); mutations go through Server Actions for the same reason. The Java backend gets two new read-only, additive endpoints (business list/search, per-consumer usage summary) reusing existing repositories and scopes — no changes to any existing endpoint.

**Tech Stack:** Backend: Java 21 / Spring Boot 3 (existing repo, `/home/sheri/code/java_project`). Frontend: Next.js 15 (App Router) + TypeScript + Tailwind CSS, Recharts for charts, Vitest for the one genuinely unit-testable module (the API client). New repo at `/home/sheri/code/credit-scoring-dashboard`.

**Spec:** `docs/superpowers/specs/2026-09-09-lender-dashboard-design.md` (this repo)

## Global Constraints

- Backend additions are read-only and additive: no existing endpoint's request/response shape changes.
- The dashboard never sends the API key to the browser — it lives only in an `httpOnly` cookie read by server-side code.
- No CORS configuration is added to the Java backend (server-to-server fetch doesn't need it).
- Deep-blue accent, neutral gray/white base, one accent color — no per-button rainbow.
- Every data view (search results, score profile, transactions, usage) has an explicit empty, loading, and error state.
- Deviation from spec, noted here for the record: the spec said "shadcn/ui as the component base." This plan hand-rolls a half-dozen small Tailwind primitives (`components/ui/`) instead of running the shadcn CLI. Reasoning: the CLI's generated markup *is* the "default shadcn look" the brief explicitly wants avoided, and a written plan can't pin exact CLI-generated output ahead of time the way it can hand-written code. The result is Tailwind-based, customized, and equally simple — same spec intent, more deterministic plan.
- No automated frontend test suite beyond the API client's unit tests (matches the spec's explicit scope decision). Every frontend task's "test" step is `npm run build` plus a stated manual browser check; only the API client task uses Vitest, because it's the one module with real branching logic worth pinning down.
- Backend task verification follows the existing repo's own established pattern (no `@SpringBootTest`/`@DataJpaTest` integration tests exist anywhere in it; `./mvnw compile` + manual curl against the local Postgres is how every prior endpoint was verified) — this plan does not introduce a new testing style for two small endpoints.

---

## Task 1: Backend — business list/search endpoint

**Files:**
- Modify: `src/main/java/com/creditscore/platform/identity/business/BusinessRepository.java`
- Modify: `src/main/java/com/creditscore/platform/identity/business/BusinessService.java`
- Modify: `src/main/java/com/creditscore/platform/api/controller/BusinessController.java`

**Interfaces:**
- Produces: `GET /api/v1/businesses?search=&page=&size=` → `200 OK` with a Spring `Page<BusinessResponse>` body (same JSON shape as the existing `GET /transactions` pagination — `content`, `totalElements`, `totalPages`, `number`, `size`, `first`, `last`). Requires the `BUSINESS_WRITE` scope (same as the existing single-business `GET`).

- [ ] **Step 1: Add the search query method to `BusinessRepository`**

Current file:
```java
package com.creditscore.platform.identity.business;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {
}
```

Replace with:
```java
package com.creditscore.platform.identity.business;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface BusinessRepository extends JpaRepository<Business, UUID> {

    Page<Business> findByNameContainingIgnoreCase(String name, Pageable pageable);
}
```

- [ ] **Step 2: Add `list`/`search` methods to `BusinessService`**

Current file:
```java
package com.creditscore.platform.identity.business;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.NoSuchElementException;
import java.util.UUID;

@Service
public class BusinessService {

    private final BusinessRepository businessRepository;

    public BusinessService(BusinessRepository businessRepository) {
        this.businessRepository = businessRepository;
    }

    @Transactional
    public Business register(String name, String country, String industry, String registrationNumber,
                              LocalDate registrationDate) {
        Business business = new Business(name, country, industry, registrationNumber, registrationDate);
        return businessRepository.save(business);
    }

    public Business getOrThrow(UUID businessId) {
        return businessRepository.findById(businessId)
                .orElseThrow(() -> new NoSuchElementException("Business not found: " + businessId));
    }
}
```

Add these two methods before the closing brace (after `getOrThrow`):
```java
    public Page<Business> list(Pageable pageable) {
        return businessRepository.findAll(pageable);
    }

    public Page<Business> search(String query, Pageable pageable) {
        return businessRepository.findByNameContainingIgnoreCase(query, pageable);
    }
```

And add these two imports alongside the existing ones:
```java
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
```

- [ ] **Step 3: Add the list endpoint to `BusinessController`**

Current file (full, for reference — see the Read above): has `register` (`POST`) and `get` (`GET /{businessId}`).

Add this method inside the class, after `register` and before `get`:
```java
    @GetMapping
    @PreAuthorize("hasAuthority('BUSINESS_WRITE')")
    public ResponseEntity<org.springframework.data.domain.Page<BusinessResponse>> list(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String search,
            org.springframework.data.domain.Pageable pageable) {
        var page = (search == null || search.isBlank())
                ? businessService.list(pageable)
                : businessService.search(search, pageable);
        return ResponseEntity.ok(page.map(BusinessResponse::from));
    }
```

(Fully-qualified names used inline to avoid adding four more import lines for a single method — matches how `TransactionController` already uses `Pageable`/`Page` via imports; here it's inline since this method is the only user of `Page` in this file. Either style compiles; this keeps the diff to one method.)

- [ ] **Step 4: Compile**

Run: `./mvnw -q compile` (from `/home/sheri/code/java_project`)
Expected: no output, exit code 0.

- [ ] **Step 5: Manual verification against the running backend**

Prerequisite: `docker compose up -d postgres` and `SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run` are running (per this repo's README), with the seed API key from the startup log.

```bash
API_KEY="<seed key from startup log>"
curl -s -H "X-API-Key: $API_KEY" "http://localhost:8080/api/v1/businesses?size=5"
curl -s -H "X-API-Key: $API_KEY" "http://localhost:8080/api/v1/businesses?search=Kenya"
curl -s -H "X-API-Key: $API_KEY" "http://localhost:8080/api/v1/businesses?search=nomatch12345"
```
Expected: first call returns a page with up to 5 of the 4 seeded businesses; second call returns however many seeded business names contain "Kenya" (likely zero, since seeded names are "Jua Kali Hardware" etc. — the point is confirming the filter runs, not a specific count); third call returns `"content":[],"totalElements":0`.

- [ ] **Step 6: Commit**

```bash
cd /home/sheri/code/java_project
git add src/main/java/com/creditscore/platform/identity/business/BusinessRepository.java \
        src/main/java/com/creditscore/platform/identity/business/BusinessService.java \
        src/main/java/com/creditscore/platform/api/controller/BusinessController.java
git commit -m "Add business list/search endpoint for the lender dashboard

GET /api/v1/businesses now supports pagination and an optional
case-insensitive name search, reusing the existing BUSINESS_WRITE scope.
Needed by the dashboard's business search/lookup screen."
```

---

## Task 2: Backend — per-consumer usage summary endpoint

**Files:**
- Modify: `src/main/java/com/creditscore/platform/billing/UsageRecordRepository.java`
- Create: `src/main/java/com/creditscore/platform/billing/EndpointUsageCount.java`
- Create: `src/main/java/com/creditscore/platform/api/dto/UsageRecordResponse.java`
- Create: `src/main/java/com/creditscore/platform/api/dto/UsageSummaryResponse.java`
- Create: `src/main/java/com/creditscore/platform/api/controller/UsageController.java`

**Interfaces:**
- Consumes: `identity.consumer.Consumer` (existing entity, resolved as the request's `Authentication` principal — see `ApiKeyAuthenticationToken.getPrincipal()`), `billing.UsageRecord` (existing entity: `consumerId`, `endpoint`, `method`, `calledAt`, `responseStatus`).
- Produces: `GET /api/v1/usage/summary` → `200 OK` with `UsageSummaryResponse` (`totalCalls: long`, `byEndpoint: EndpointUsageCount[]`, `recentCalls: UsageRecordResponse[]`). Requires only authentication — no new scope, since a consumer reading their own usage isn't a new permission.

- [ ] **Step 1: Add query methods to `UsageRecordRepository`**

Current file:
```java
package com.creditscore.platform.billing;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {
}
```

Replace with:
```java
package com.creditscore.platform.billing;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {

    long countByConsumerId(UUID consumerId);

    List<UsageRecord> findTop20ByConsumerIdOrderByCalledAtDesc(UUID consumerId);

    @Query("select u.endpoint as endpoint, count(u) as callCount from UsageRecord u "
            + "where u.consumerId = :consumerId group by u.endpoint order by count(u) desc")
    List<EndpointUsageCount> countByEndpointForConsumer(@Param("consumerId") UUID consumerId);
}
```

- [ ] **Step 2: Create the repository projection interface**

Create `src/main/java/com/creditscore/platform/billing/EndpointUsageCount.java`:
```java
package com.creditscore.platform.billing;

/**
 * Spring Data JPA interface-based projection for the grouped-by-endpoint query in
 * {@link UsageRecordRepository#countByEndpointForConsumer}. Getter names must match
 * the query's result aliases (endpoint, callCount).
 */
public interface EndpointUsageCount {

    String getEndpoint();

    long getCallCount();
}
```

- [ ] **Step 3: Create the response DTOs**

Create `src/main/java/com/creditscore/platform/api/dto/UsageRecordResponse.java`:
```java
package com.creditscore.platform.api.dto;

import com.creditscore.platform.billing.UsageRecord;

import java.time.Instant;

public record UsageRecordResponse(
        String endpoint,
        String method,
        Instant calledAt,
        int responseStatus
) {
    public static UsageRecordResponse from(UsageRecord record) {
        return new UsageRecordResponse(record.getEndpoint(), record.getMethod(), record.getCalledAt(),
                record.getResponseStatus());
    }
}
```

Create `src/main/java/com/creditscore/platform/api/dto/UsageSummaryResponse.java`:
```java
package com.creditscore.platform.api.dto;

import java.util.List;

public record UsageSummaryResponse(
        long totalCalls,
        List<EndpointCount> byEndpoint,
        List<UsageRecordResponse> recentCalls
) {
    public record EndpointCount(String endpoint, long count) {
    }
}
```

- [ ] **Step 4: Create the controller**

Create `src/main/java/com/creditscore/platform/api/controller/UsageController.java`:
```java
package com.creditscore.platform.api.controller;

import com.creditscore.platform.api.dto.UsageRecordResponse;
import com.creditscore.platform.api.dto.UsageSummaryResponse;
import com.creditscore.platform.billing.UsageRecordRepository;
import com.creditscore.platform.identity.consumer.Consumer;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/usage")
public class UsageController {

    private final UsageRecordRepository usageRecordRepository;

    public UsageController(UsageRecordRepository usageRecordRepository) {
        this.usageRecordRepository = usageRecordRepository;
    }

    @GetMapping("/summary")
    public UsageSummaryResponse summary(@AuthenticationPrincipal Consumer consumer) {
        var byEndpoint = usageRecordRepository.countByEndpointForConsumer(consumer.getId()).stream()
                .map(row -> new UsageSummaryResponse.EndpointCount(row.getEndpoint(), row.getCallCount()))
                .toList();
        var recentCalls = usageRecordRepository.findTop20ByConsumerIdOrderByCalledAtDesc(consumer.getId()).stream()
                .map(UsageRecordResponse::from)
                .toList();
        long totalCalls = usageRecordRepository.countByConsumerId(consumer.getId());

        return new UsageSummaryResponse(totalCalls, byEndpoint, recentCalls);
    }
}
```

Note: `@AuthenticationPrincipal Consumer consumer` resolves from `SecurityContext.getAuthentication().getPrincipal()`, which `ApiKeyAuthenticationToken` (in `identity.auth`) already returns as the `Consumer` entity — no new wiring needed. This endpoint requires no `@PreAuthorize`; `SecurityConfig`'s `apiFilterChain` already requires authentication for everything under `/api/**`, and a consumer reading only their own usage needs no additional scope.

- [ ] **Step 5: Compile**

Run: `./mvnw -q compile`
Expected: no output, exit code 0.

- [ ] **Step 6: Manual verification**

With the app running (seed profile) and a few prior calls already made against it (e.g. from Task 1's verification), so `byEndpoint`/`recentCalls` aren't empty:
```bash
API_KEY="<seed key from startup log>"
curl -s -H "X-API-Key: $API_KEY" "http://localhost:8080/api/v1/usage/summary"
```
Expected: JSON with `totalCalls` > 0, `byEndpoint` listing at least `/api/v1/businesses`-shaped entries with counts, `recentCalls` with up to 20 entries newest-first. Also verify it's per-consumer, not global: if a second `Consumer` existed it would see only its own rows — not tested here since only one consumer exists in seed data, but confirm by reading `UsageController.summary` that every query is scoped by `consumer.getId()` (it is, by construction).

- [ ] **Step 7: Commit**

```bash
cd /home/sheri/code/java_project
git add src/main/java/com/creditscore/platform/billing/UsageRecordRepository.java \
        src/main/java/com/creditscore/platform/billing/EndpointUsageCount.java \
        src/main/java/com/creditscore/platform/api/dto/UsageRecordResponse.java \
        src/main/java/com/creditscore/platform/api/dto/UsageSummaryResponse.java \
        src/main/java/com/creditscore/platform/api/controller/UsageController.java
git commit -m "Add per-consumer usage summary endpoint for the lender dashboard

GET /api/v1/usage/summary returns the authenticated consumer's own total
call count, a breakdown by endpoint, and the 20 most recent calls, reading
back the UsageRecord rows JpaUsageMeter already writes on every
authenticated request. No new scope: reading your own usage isn't a new
permission. Needed by the dashboard's usage overview screen."
```

---

## Task 3: Frontend — scaffold the Next.js project

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/` (entire `create-next-app` output)

**Interfaces:**
- Produces: a runnable Next.js 15 App Router + TypeScript + Tailwind project at `/home/sheri/code/credit-scoring-dashboard`, with its own git repo, that later tasks add files into.

- [ ] **Step 1: Scaffold with `create-next-app`**

```bash
cd /home/sheri/code
npx --yes create-next-app@latest credit-scoring-dashboard \
  --typescript --tailwind --eslint --app --src-dir=false \
  --import-alias "@/*" --use-npm --no-turbopack --yes
```

- [ ] **Step 2: Verify the default scaffold builds**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds (`✓ Compiled successfully`).

- [ ] **Step 3: Add the Java API base URL env var**

Create `/home/sheri/code/credit-scoring-dashboard/.env.local`:
```
JAVA_API_BASE_URL=http://localhost:8080/api/v1
```

Create `/home/sheri/code/credit-scoring-dashboard/.env.example`:
```
JAVA_API_BASE_URL=http://localhost:8080/api/v1
```

Confirm `.gitignore` (from the scaffold) already excludes `.env*.local` — `create-next-app`'s default `.gitignore` does this out of the box; open the file and check for a `.env*.local` line before proceeding.

- [ ] **Step 4: Initialize git and make the first commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git init
git add -A
git commit -m "Scaffold Next.js dashboard project

create-next-app with TypeScript, Tailwind, App Router, ESLint. Base for
the lender-facing dashboard consuming the SME credit-scoring API."
```

---

## Task 4: Frontend — design tokens and base UI primitives

**Files:**
- Modify: `/home/sheri/code/credit-scoring-dashboard/tailwind.config.ts` (or `.js`, whichever `create-next-app` generated — check first)
- Modify: `/home/sheri/code/credit-scoring-dashboard/app/globals.css`
- Modify: `/home/sheri/code/credit-scoring-dashboard/app/layout.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/button.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/badge.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/card.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/skeleton.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/empty-state.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/ui/error-state.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/cn.ts`

**Interfaces:**
- Produces: `cn(...classes: (string | undefined | false)[]): string` from `lib/cn.ts`; `<Button>`, `<Badge tone="neutral"|"success"|"warning"|"danger">`, `<Card>`, `<Skeleton className? />`, `<EmptyState title description icon? />`, `<ErrorState title message />` React components from `components/ui/*`, all imported by later tasks.

- [ ] **Step 1: Check which Tailwind config file exists**

```bash
ls /home/sheri/code/credit-scoring-dashboard/tailwind.config.*
```
Next.js 15's `create-next-app --tailwind` generates `tailwind.config.ts`. Use that path in the next step (adjust if the scaffold produced `.js` instead).

- [ ] **Step 2: Customize the Tailwind theme**

Replace the contents of `tailwind.config.ts` with:
```ts
import type { Config } from "tailwindcss";

const config: Config = {
  content: ["./app/**/*.{ts,tsx}", "./components/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        brand: {
          50: "#eef4ff",
          100: "#dbe7fe",
          200: "#bfd6fe",
          300: "#93bbfd",
          400: "#6096fa",
          500: "#3b74f6",
          600: "#2554eb",
          700: "#1e40c9",
          800: "#1e3aa3",
          900: "#1c3480",
        },
      },
      fontFamily: {
        sans: ["var(--font-sans)", "system-ui", "sans-serif"],
        serif: ["var(--font-serif)", "Georgia", "serif"],
      },
      borderRadius: {
        sm: "0.25rem",
        DEFAULT: "0.5rem",
        md: "0.625rem",
        lg: "0.875rem",
        xl: "1.25rem",
      },
      spacing: {
        "4.5": "1.125rem",
        "13": "3.25rem",
        "18": "4.5rem",
      },
      fontSize: {
        xs: ["0.75rem", { lineHeight: "1.1rem" }],
        sm: ["0.8125rem", { lineHeight: "1.25rem" }],
        base: ["0.9375rem", { lineHeight: "1.5rem" }],
        lg: ["1.0625rem", { lineHeight: "1.6rem" }],
        xl: ["1.25rem", { lineHeight: "1.75rem" }],
        "2xl": ["1.5rem", { lineHeight: "2rem" }],
        "3xl": ["1.875rem", { lineHeight: "2.25rem" }],
      },
    },
  },
  plugins: [],
};

export default config;
```

- [ ] **Step 3: Set up fonts and base body styles in the root layout**

Replace `/home/sheri/code/credit-scoring-dashboard/app/layout.tsx` with:
```tsx
import type { Metadata } from "next";
import { Inter, Lora } from "next/font/google";
import "./globals.css";

const sans = Inter({ subsets: ["latin"], variable: "--font-sans" });
const serif = Lora({ subsets: ["latin"], variable: "--font-serif", weight: ["500", "600"] });

export const metadata: Metadata = {
  title: "Credit Scoring Dashboard",
  description: "Lender dashboard for the SME credit-scoring platform",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en" className={`${sans.variable} ${serif.variable}`}>
      <body className="min-h-screen bg-neutral-50 font-sans text-neutral-900 antialiased">
        {children}
      </body>
    </html>
  );
}
```

- [ ] **Step 4: Trim `globals.css` down to Tailwind's base layers**

Replace `/home/sheri/code/credit-scoring-dashboard/app/globals.css` with:
```css
@tailwind base;
@tailwind components;
@tailwind utilities;

h1, h2, h3 {
  font-family: var(--font-serif);
}
```

- [ ] **Step 5: Add the `cn` class-merging helper**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm install clsx tailwind-merge
```

Create `lib/cn.ts`:
```ts
import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}
```

- [ ] **Step 6: Create the `Button` primitive**

Create `components/ui/button.tsx`:
```tsx
import { ButtonHTMLAttributes, forwardRef } from "react";
import { cn } from "@/lib/cn";

type Variant = "primary" | "secondary" | "ghost";

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: Variant;
}

const variantClasses: Record<Variant, string> = {
  primary: "bg-brand-700 text-white hover:bg-brand-800 disabled:bg-brand-300",
  secondary:
    "bg-white text-neutral-900 border border-neutral-300 hover:bg-neutral-50 disabled:text-neutral-400",
  ghost: "bg-transparent text-neutral-700 hover:bg-neutral-100 disabled:text-neutral-400",
};

export const Button = forwardRef<HTMLButtonElement, ButtonProps>(
  ({ className, variant = "primary", ...props }, ref) => (
    <button
      ref={ref}
      className={cn(
        "inline-flex items-center justify-center rounded-lg px-4 py-2 text-sm font-medium transition disabled:cursor-not-allowed",
        variantClasses[variant],
        className
      )}
      {...props}
    />
  )
);
Button.displayName = "Button";
```

- [ ] **Step 7: Create the `Badge` primitive**

Create `components/ui/badge.tsx`:
```tsx
import { cn } from "@/lib/cn";

type Tone = "neutral" | "success" | "warning" | "danger";

const toneClasses: Record<Tone, string> = {
  neutral: "bg-neutral-100 text-neutral-700",
  success: "bg-emerald-100 text-emerald-800",
  warning: "bg-amber-100 text-amber-800",
  danger: "bg-red-100 text-red-800",
};

export function Badge({ tone = "neutral", children }: { tone?: Tone; children: React.ReactNode }) {
  return (
    <span className={cn("inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium", toneClasses[tone])}>
      {children}
    </span>
  );
}
```

- [ ] **Step 8: Create the `Card` primitive**

Create `components/ui/card.tsx`:
```tsx
import { cn } from "@/lib/cn";

export function Card({ className, children }: { className?: string; children: React.ReactNode }) {
  return (
    <div className={cn("rounded-xl border border-neutral-200 bg-white p-6 shadow-sm", className)}>
      {children}
    </div>
  );
}
```

- [ ] **Step 9: Create the `Skeleton`, `EmptyState`, and `ErrorState` primitives**

Create `components/ui/skeleton.tsx`:
```tsx
import { cn } from "@/lib/cn";

export function Skeleton({ className }: { className?: string }) {
  return <div className={cn("animate-pulse rounded-md bg-neutral-200", className)} />;
}
```

Create `components/ui/empty-state.tsx`:
```tsx
export function EmptyState({ title, description }: { title: string; description?: string }) {
  return (
    <div className="flex flex-col items-center justify-center rounded-xl border border-dashed border-neutral-300 px-6 py-16 text-center">
      <p className="text-sm font-medium text-neutral-700">{title}</p>
      {description && <p className="mt-1 text-sm text-neutral-500">{description}</p>}
    </div>
  );
}
```

Create `components/ui/error-state.tsx`:
```tsx
export function ErrorState({ title, message }: { title: string; message: string }) {
  return (
    <div className="rounded-xl border border-red-200 bg-red-50 px-6 py-8 text-center">
      <p className="text-sm font-medium text-red-800">{title}</p>
      <p className="mt-1 text-sm text-red-600">{message}</p>
    </div>
  );
}
```

- [ ] **Step 10: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds. (The primitives aren't imported anywhere yet, so this mainly confirms no syntax/type errors in the new files — TypeScript still checks unreferenced files during the build.)

- [ ] **Step 11: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add -A
git commit -m "Add custom design tokens and hand-rolled UI primitives

Deep-blue accent palette, custom spacing/radius/type-scale overrides, and
a small set of Tailwind-based primitives (Button, Badge, Card, Skeleton,
EmptyState, ErrorState) instead of shadcn's CLI-generated components, so
the dashboard doesn't read as an out-of-the-box template."
```

---

## Task 5: Frontend — API client with types and unit tests

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/types.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/api-client.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/api-client.test.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/vitest.config.ts`
- Modify: `/home/sheri/code/credit-scoring-dashboard/package.json` (add `test` script)

**Interfaces:**
- Produces: `createApiClient({ apiKey, baseUrl? })` returning `{ listBusinesses, getBusiness, createBusiness, createDataSource, triggerSync, getScore, getScoreHistory, getTransactions, getUsageSummary }`, and `class ApiError extends Error { status: number }`. All exported from `lib/api-client.ts`. Types (`Business`, `PageResponse<T>`, `ScoreProfile`, `ScoreFactor`, `Transaction`, `UsageSummary`) exported from `lib/types.ts`. Every later frontend task that fetches data imports from here — no raw `fetch` calls anywhere else in the app.

- [ ] **Step 1: Install Vitest**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm install -D vitest
```

- [ ] **Step 2: Add the Vitest config and `test` script**

Create `vitest.config.ts`:
```ts
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    environment: "node",
    include: ["**/*.test.ts"],
  },
});
```

In `package.json`, add `"test": "vitest run"` to the `"scripts"` object (alongside the existing `dev`/`build`/`start`/`lint` entries).

- [ ] **Step 3: Write the shared response types**

Create `lib/types.ts`:
```ts
export type BusinessStatus = "PENDING" | "ACTIVE" | "SUSPENDED" | "REJECTED";

export interface Business {
  id: string;
  name: string;
  country: string;
  industry: string | null;
  registrationNumber: string | null;
  registrationDate: string | null;
  onboardingDate: string;
  status: BusinessStatus;
}

export interface PageResponse<T> {
  content: T[];
  totalElements: number;
  totalPages: number;
  number: number;
  size: number;
  first: boolean;
  last: boolean;
}

export type ConfidenceLevel = "LOW" | "MEDIUM" | "HIGH";
export type ModelType = "RULE_BASED" | "ML";

export interface ScoreFactor {
  name: string;
  weight: number;
  rawValue: number;
  normalizedValue: number;
  contribution: number;
  explanation: string;
}

export interface ScoreProfile {
  businessId: string;
  score: number;
  confidence: number;
  confidenceLevel: ConfidenceLevel;
  factors: ScoreFactor[];
  modelVersion: string;
  modelType: ModelType;
  windowStart: string | null;
  windowEnd: string | null;
  generatedAt: string;
}

export type TransactionDirection = "INFLOW" | "OUTFLOW";
export type TransactionStatus = "COMPLETED" | "FAILED";

export interface Transaction {
  id: string;
  dataSourceId: string;
  externalReference: string;
  amount: number;
  currency: string;
  transactionDate: string;
  direction: TransactionDirection;
  counterparty: string | null;
  sourceType: string;
  status: TransactionStatus;
}

export interface SyncResult {
  status: "SUCCESS" | "PARTIAL" | "FAILED";
  transactionsInserted: number;
  transactionsSkipped: number;
}

export interface UsageEndpointCount {
  endpoint: string;
  count: number;
}

export interface UsageRecordSummary {
  endpoint: string;
  method: string;
  calledAt: string;
  responseStatus: number;
}

export interface UsageSummary {
  totalCalls: number;
  byEndpoint: UsageEndpointCount[];
  recentCalls: UsageRecordSummary[];
}
```

- [ ] **Step 4: Write the failing tests for the API client**

Create `lib/api-client.test.ts`:
```ts
import { describe, it, expect, vi, beforeEach } from "vitest";
import { createApiClient, ApiError } from "./api-client";

describe("api-client", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("sends the API key header and parses a successful JSON response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ id: "abc", name: "Test Co" }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const client = createApiClient({ apiKey: "csk_test", baseUrl: "http://localhost:9999/api/v1" });
    const result = await client.getBusiness("abc");

    expect(result).toEqual({ id: "abc", name: "Test Co" });
    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:9999/api/v1/businesses/abc",
      expect.objectContaining({
        headers: expect.objectContaining({ "X-API-Key": "csk_test" }),
      })
    );
  });

  it("throws an ApiError carrying the backend's message on a non-2xx response", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 404,
      statusText: "Not Found",
      json: async () => ({ error: "not_found", message: "Business not found: abc" }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const client = createApiClient({ apiKey: "csk_test", baseUrl: "http://localhost:9999/api/v1" });

    await expect(client.getBusiness("abc")).rejects.toBeInstanceOf(ApiError);
    await expect(client.getBusiness("abc")).rejects.toThrow("Business not found: abc");
  });

  it("falls back to statusText when the error response body isn't JSON", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: false,
      status: 500,
      statusText: "Internal Server Error",
      json: async () => {
        throw new Error("not json");
      },
    });
    vi.stubGlobal("fetch", fetchMock);

    const client = createApiClient({ apiKey: "csk_test", baseUrl: "http://localhost:9999/api/v1" });

    await expect(client.getBusiness("abc")).rejects.toThrow("Internal Server Error");
  });

  it("builds the search query string only when a search term is given", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ content: [], totalElements: 0, totalPages: 0, number: 0, size: 20, first: true, last: true }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const client = createApiClient({ apiKey: "csk_test", baseUrl: "http://localhost:9999/api/v1" });
    await client.listBusinesses();
    await client.listBusinesses("acme");

    expect(fetchMock).toHaveBeenNthCalledWith(1, "http://localhost:9999/api/v1/businesses?page=0&size=20", expect.anything());
    expect(fetchMock).toHaveBeenNthCalledWith(
      2,
      "http://localhost:9999/api/v1/businesses?page=0&size=20&search=acme",
      expect.anything()
    );
  });

  it("sends a POST with a JSON body for triggerSync and parses the SyncResult", async () => {
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      json: async () => ({ status: "SUCCESS", transactionsInserted: 5, transactionsSkipped: 0 }),
    });
    vi.stubGlobal("fetch", fetchMock);

    const client = createApiClient({ apiKey: "csk_test", baseUrl: "http://localhost:9999/api/v1" });
    const result = await client.triggerSync("biz-1");

    expect(result).toEqual({ status: "SUCCESS", transactionsInserted: 5, transactionsSkipped: 0 });
    expect(fetchMock).toHaveBeenCalledWith(
      "http://localhost:9999/api/v1/businesses/biz-1/sync",
      expect.objectContaining({ method: "POST" })
    );
  });
});
```

- [ ] **Step 5: Run the tests and confirm they fail**

Run: `npx vitest run`
Expected: FAIL — `lib/api-client.ts` doesn't exist yet (`Cannot find module './api-client'` or similar).

- [ ] **Step 6: Implement the API client**

Create `lib/api-client.ts`:
```ts
import type {
  Business,
  PageResponse,
  ScoreProfile,
  SyncResult,
  Transaction,
  UsageSummary,
} from "./types";

export class ApiError extends Error {
  status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

interface ApiClientOptions {
  apiKey: string;
  baseUrl?: string;
}

async function request<T>(path: string, apiKey: string, baseUrl: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${baseUrl}${path}`, {
    ...init,
    headers: {
      "X-API-Key": apiKey,
      "Content-Type": "application/json",
      ...(init?.headers ?? {}),
    },
    cache: "no-store",
  });

  if (!res.ok) {
    let message: string = res.statusText;
    try {
      const body = await res.json();
      if (body && typeof body.message === "string") {
        message = body.message;
      }
    } catch {
      // Response body wasn't JSON; keep the statusText fallback.
    }
    throw new ApiError(res.status, message);
  }

  if (res.status === 204) {
    return undefined as T;
  }
  return (await res.json()) as T;
}

export function createApiClient({
  apiKey,
  baseUrl = process.env.JAVA_API_BASE_URL ?? "http://localhost:8080/api/v1",
}: ApiClientOptions) {
  return {
    listBusinesses(search?: string, page = 0, size = 20) {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (search) {
        params.set("search", search);
      }
      return request<PageResponse<Business>>(`/businesses?${params.toString()}`, apiKey, baseUrl);
    },

    getBusiness(id: string) {
      return request<Business>(`/businesses/${id}`, apiKey, baseUrl);
    },

    createBusiness(input: {
      name: string;
      country: string;
      industry?: string;
      registrationNumber?: string;
      registrationDate?: string;
    }) {
      return request<Business>("/businesses", apiKey, baseUrl, {
        method: "POST",
        body: JSON.stringify(input),
      });
    },

    createDataSource(businessId: string, input: { adapterType: string; provider?: string }) {
      return request<unknown>(`/businesses/${businessId}/data-sources`, apiKey, baseUrl, {
        method: "POST",
        body: JSON.stringify(input),
      });
    },

    triggerSync(businessId: string) {
      return request<SyncResult>(`/businesses/${businessId}/sync`, apiKey, baseUrl, { method: "POST" });
    },

    getScore(businessId: string) {
      return request<ScoreProfile>(`/businesses/${businessId}/score`, apiKey, baseUrl);
    },

    getScoreHistory(businessId: string) {
      return request<ScoreProfile[]>(`/businesses/${businessId}/score/history`, apiKey, baseUrl);
    },

    getTransactions(businessId: string, page = 0, size = 10) {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      return request<PageResponse<Transaction>>(
        `/businesses/${businessId}/transactions?${params.toString()}`,
        apiKey,
        baseUrl
      );
    },

    getUsageSummary() {
      return request<UsageSummary>("/usage/summary", apiKey, baseUrl);
    },
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
```

- [ ] **Step 7: Run the tests and confirm they pass**

Run: `npx vitest run`
Expected: PASS — 5 tests, all green.

- [ ] **Step 8: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add lib/types.ts lib/api-client.ts lib/api-client.test.ts vitest.config.ts package.json package-lock.json
git commit -m "Add typed Java API client with unit tests

Single fetch wrapper (lib/api-client.ts) every server component/action
goes through — attaches X-API-Key, maps non-2xx responses to a typed
ApiError, no raw fetch calls scattered around the app. Covered by Vitest
against a mocked global fetch."
```

---

## Task 6: Frontend — session cookie, login, and route protection

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/constants.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/lib/session.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/middleware.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/app/login/actions.ts`
- Create: `/home/sheri/code/credit-scoring-dashboard/app/login/page.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/app/actions/logout.ts`

**Interfaces:**
- Consumes: `createApiClient`, `ApiError` from `lib/api-client.ts` (Task 5).
- Produces: `getApiKey(): Promise<string | null>`, `setApiKey(key: string): Promise<void>`, `clearApiKey(): Promise<void>` from `lib/session.ts`; `logout(): Promise<void>` Server Action from `app/actions/logout.ts`. Later tasks (7-10) call `getApiKey()` to build an API client per request and `logout()` for the nav bar's sign-out button.

- [ ] **Step 1: Add the shared cookie-name constant**

Create `lib/constants.ts`:
```ts
export const SESSION_COOKIE_NAME = "dashboard_api_key";
```

- [ ] **Step 2: Add the session helper**

Create `lib/session.ts`:
```ts
import { cookies } from "next/headers";
import { SESSION_COOKIE_NAME } from "./constants";

export async function getApiKey(): Promise<string | null> {
  const store = await cookies();
  return store.get(SESSION_COOKIE_NAME)?.value ?? null;
}

export async function setApiKey(apiKey: string): Promise<void> {
  const store = await cookies();
  store.set(SESSION_COOKIE_NAME, apiKey, {
    httpOnly: true,
    secure: process.env.NODE_ENV === "production",
    sameSite: "lax",
    path: "/",
    maxAge: 60 * 60 * 24 * 7,
  });
}

export async function clearApiKey(): Promise<void> {
  const store = await cookies();
  store.delete(SESSION_COOKIE_NAME);
}
```

- [ ] **Step 3: Add route-protection middleware**

Create `middleware.ts` (project root, alongside `package.json`):
```ts
import { NextRequest, NextResponse } from "next/server";
import { SESSION_COOKIE_NAME } from "./lib/constants";

const PUBLIC_PATHS = ["/login"];

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl;
  const isPublicPath = PUBLIC_PATHS.some((path) => pathname.startsWith(path));
  const hasSession = request.cookies.has(SESSION_COOKIE_NAME);

  if (!hasSession && !isPublicPath) {
    return NextResponse.redirect(new URL("/login", request.url));
  }
  if (hasSession && pathname === "/login") {
    return NextResponse.redirect(new URL("/businesses", request.url));
  }
  return NextResponse.next();
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico).*)"],
};
```

- [ ] **Step 4: Add the login Server Action**

Create `app/login/actions.ts`:
```ts
"use server";

import { redirect } from "next/navigation";
import { createApiClient, ApiError } from "@/lib/api-client";
import { setApiKey } from "@/lib/session";

export interface LoginState {
  error: string | null;
}

export async function login(_prevState: LoginState, formData: FormData): Promise<LoginState> {
  const apiKey = String(formData.get("apiKey") ?? "").trim();
  if (!apiKey) {
    return { error: "Enter an API key." };
  }

  const client = createApiClient({ apiKey });
  try {
    await client.getUsageSummary();
  } catch (err) {
    if (err instanceof ApiError && err.status === 401) {
      return { error: "Invalid API key." };
    }
    return { error: "Could not reach the API. Is the backend running?" };
  }

  await setApiKey(apiKey);
  redirect("/businesses");
}
```

- [ ] **Step 5: Add the login page**

Create `app/login/page.tsx`:
```tsx
"use client";

import { useActionState } from "react";
import { login, type LoginState } from "./actions";

const initialState: LoginState = { error: null };

export default function LoginPage() {
  const [state, formAction, pending] = useActionState(login, initialState);

  return (
    <main className="flex min-h-screen items-center justify-center px-4">
      <div className="w-full max-w-sm rounded-xl border border-neutral-200 bg-white p-8 shadow-sm">
        <h1 className="text-xl font-semibold text-neutral-900">Sign in</h1>
        <p className="mt-1 text-sm text-neutral-500">Enter your consumer API key to access the dashboard.</p>
        <form action={formAction} className="mt-6 space-y-4">
          <div>
            <label htmlFor="apiKey" className="block text-sm font-medium text-neutral-700">
              API key
            </label>
            <input
              id="apiKey"
              name="apiKey"
              type="password"
              autoComplete="off"
              required
              className="mt-1 w-full rounded-lg border border-neutral-300 px-3 py-2 text-sm focus:border-brand-600 focus:outline-none focus:ring-1 focus:ring-brand-600"
              placeholder="csk_..."
            />
          </div>
          {state.error && (
            <p className="text-sm text-red-600" role="alert">
              {state.error}
            </p>
          )}
          <button
            type="submit"
            disabled={pending}
            className="w-full rounded-lg bg-brand-700 px-4 py-2 text-sm font-medium text-white transition hover:bg-brand-800 disabled:opacity-50"
          >
            {pending ? "Signing in..." : "Sign in"}
          </button>
        </form>
      </div>
    </main>
  );
}
```

- [ ] **Step 6: Add the logout Server Action**

Create `app/actions/logout.ts`:
```ts
"use server";

import { redirect } from "next/navigation";
import { clearApiKey } from "@/lib/session";

export async function logout() {
  await clearApiKey();
  redirect("/login");
}
```

- [ ] **Step 7: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds.

- [ ] **Step 8: Manual verification**

With the Java backend running (`docker compose up -d postgres` + `SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run` in `/home/sheri/code/java_project`):
```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run dev
```
In a browser: visiting `http://localhost:3000/` (or any path) redirects to `/login` (no session cookie yet — `/businesses` doesn't exist as a page until Task 8, so a 404 after redirect is expected at this point; confirm the *redirect to `/login`* happens, which is what this task delivers). Enter an invalid key → inline "Invalid API key." error, no redirect. Enter the seed API key from the backend's startup log → redirects toward `/businesses` (404 is fine for now, since that page doesn't exist until Task 8 — what matters here is the cookie got set and the redirect fired).

- [ ] **Step 9: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add lib/constants.ts lib/session.ts middleware.ts app/login app/actions
git commit -m "Add API-key login, httpOnly session cookie, and route protection

Login validates the key against GET /usage/summary before setting an
httpOnly, SameSite=Lax cookie via a Server Action. Middleware redirects
unauthenticated requests to /login and authenticated ones away from it.
The API key never reaches browser JavaScript."
```

---

## Task 7: Frontend — dashboard shell (nav layout)

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/app/(dashboard)/layout.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/nav-bar.tsx`

**Interfaces:**
- Consumes: `logout` from `app/actions/logout.ts` (Task 6).
- Produces: a layout wrapping every page under the `(dashboard)` route group (Tasks 8-10 live inside it) with a top nav bar (Businesses / Usage links + sign-out button).

- [ ] **Step 1: Create the nav bar component**

Create `components/nav-bar.tsx`:
```tsx
import Link from "next/link";
import { logout } from "@/app/actions/logout";

export function NavBar() {
  return (
    <header className="border-b border-neutral-200 bg-white">
      <div className="mx-auto flex max-w-5xl items-center justify-between px-6 py-4">
        <div className="flex items-center gap-8">
          <span className="font-serif text-lg font-semibold text-neutral-900">Credit Scoring</span>
          <nav className="flex gap-6 text-sm font-medium text-neutral-600">
            <Link href="/businesses" className="hover:text-brand-700">
              Businesses
            </Link>
            <Link href="/usage" className="hover:text-brand-700">
              Usage
            </Link>
          </nav>
        </div>
        <form action={logout}>
          <button type="submit" className="text-sm font-medium text-neutral-500 hover:text-neutral-800">
            Sign out
          </button>
        </form>
      </div>
    </header>
  );
}
```

- [ ] **Step 2: Create the route-group layout**

Create `app/(dashboard)/layout.tsx`:
```tsx
import { NavBar } from "@/components/nav-bar";

export default function DashboardLayout({ children }: { children: React.ReactNode }) {
  return (
    <div className="min-h-screen">
      <NavBar />
      <main className="mx-auto max-w-5xl px-6 py-8">{children}</main>
    </div>
  );
}
```

- [ ] **Step 3: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds. (The `(dashboard)` group renders nothing browsable yet — no `page.tsx` exists inside it until Task 8 — this step only confirms the layout itself compiles.)

- [ ] **Step 4: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add "app/(dashboard)/layout.tsx" components/nav-bar.tsx
git commit -m "Add dashboard shell layout with nav bar and sign-out"
```

---

## Task 8: Frontend — business search/lookup page

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/app/(dashboard)/businesses/page.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/app/(dashboard)/businesses/search-box.tsx`

**Interfaces:**
- Consumes: `getApiKey` (Task 6), `createApiClient`, `ApiError`, `Business`, `PageResponse<Business>` (Task 5), `Card`, `Badge`, `EmptyState`, `ErrorState` (Task 4).
- Produces: `/businesses` page, linking each result to `/businesses/[id]` (built in Task 9).

- [ ] **Step 1: Create the client-side search box**

Create `app/(dashboard)/businesses/search-box.tsx`:
```tsx
"use client";

import { useRouter, useSearchParams, usePathname } from "next/navigation";
import { useTransition } from "react";

export function SearchBox() {
  const router = useRouter();
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const [isPending, startTransition] = useTransition();

  function handleChange(value: string) {
    const params = new URLSearchParams(searchParams.toString());
    if (value) {
      params.set("search", value);
    } else {
      params.delete("search");
    }
    startTransition(() => {
      router.replace(`${pathname}?${params.toString()}`);
    });
  }

  return (
    <input
      type="search"
      defaultValue={searchParams.get("search") ?? ""}
      onChange={(e) => handleChange(e.target.value)}
      placeholder="Search businesses by name..."
      className="w-full max-w-md rounded-lg border border-neutral-300 px-3 py-2 text-sm focus:border-brand-600 focus:outline-none focus:ring-1 focus:ring-brand-600"
      style={{ opacity: isPending ? 0.7 : 1 }}
    />
  );
}
```

Note: this updates the URL's `search` query param on every keystroke (no debounce timer — `router.replace` inside a transition already keeps typing responsive without blocking on the navigation, and avoids the extra complexity of a manual debounce for a search index this small). If typing feels like it fires too many server round-trips once real usage shows it, add a debounce here later; not needed for the MVP dataset size.

- [ ] **Step 2: Create the page (Server Component)**

Create `app/(dashboard)/businesses/page.tsx`:
```tsx
import Link from "next/link";
import { getApiKey } from "@/lib/session";
import { createApiClient, ApiError } from "@/lib/api-client";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { SearchBox } from "./search-box";

const STATUS_TONE = {
  ACTIVE: "success",
  PENDING: "neutral",
  SUSPENDED: "warning",
  REJECTED: "danger",
} as const;

export default async function BusinessesPage({
  searchParams,
}: {
  searchParams: Promise<{ search?: string }>;
}) {
  const { search } = await searchParams;
  const apiKey = await getApiKey();
  if (!apiKey) {
    return <ErrorState title="Not signed in" message="Your session expired. Please sign in again." />;
  }

  const client = createApiClient({ apiKey });

  try {
    const page = await client.listBusinesses(search, 0, 50);

    return (
      <div className="space-y-6">
        <div className="flex items-center justify-between">
          <h1 className="text-2xl font-semibold">Businesses</h1>
          <SearchBox />
        </div>

        {page.content.length === 0 ? (
          <EmptyState
            title="No businesses found"
            description={search ? `No results for "${search}".` : "No businesses have been registered yet."}
          />
        ) : (
          <Card className="divide-y divide-neutral-100 p-0">
            {page.content.map((business) => (
              <Link
                key={business.id}
                href={`/businesses/${business.id}`}
                className="flex items-center justify-between px-6 py-4 hover:bg-neutral-50"
              >
                <div>
                  <p className="font-medium text-neutral-900">{business.name}</p>
                  <p className="text-sm text-neutral-500">
                    {business.country} · {business.industry ?? "Unknown industry"}
                  </p>
                </div>
                <Badge tone={STATUS_TONE[business.status]}>{business.status}</Badge>
              </Link>
            ))}
          </Card>
        )}
      </div>
    );
  } catch (err) {
    const message = err instanceof ApiError ? err.message : "Unexpected error loading businesses.";
    return <ErrorState title="Couldn't load businesses" message={message} />;
  }
}
```

- [ ] **Step 3: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds.

- [ ] **Step 4: Manual verification**

With both the Java backend (seed profile) and `npm run dev` running: sign in at `/login` with the seed key, land on `/businesses`, confirm the 3 seeded businesses (Kenya/Nigeria/Ghana) render with correct status badges; type into the search box and confirm the URL's `?search=` updates and the list filters; search for something with no matches and confirm the empty state renders; clear the session cookie (dev tools → Application → Cookies) and reload to confirm it redirects to `/login`.

- [ ] **Step 5: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add "app/(dashboard)/businesses"
git commit -m "Add business search/lookup page

Server Component fetches GET /businesses via the API client using the
session cookie's key; client-side SearchBox drives the ?search= query
param. Explicit empty and error states."
```

---

## Task 9: Frontend — business score profile page

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/app/(dashboard)/businesses/[id]/page.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/score-trend-chart.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/factor-breakdown-chart.tsx`

**Interfaces:**
- Consumes: `getApiKey`, `createApiClient`, `ApiError`, `ScoreProfile`, `Transaction`, `PageResponse<Transaction>` (Tasks 5-6), `Card`, `Badge`, `EmptyState`, `ErrorState` (Task 4).
- Produces: `/businesses/[id]` page; `<ScoreTrendChart data={{ generatedAt: string; score: number }[]} />` and `<FactorBreakdownChart factors={ScoreFactor[]} />` client components (charts need the DOM/interactivity, so they're the only client components on this route — data is fetched server-side and passed down as props, per the spec).

- [ ] **Step 1: Install Recharts**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm install recharts
```

- [ ] **Step 2: Create the score-trend line chart**

Create `components/score-trend-chart.tsx`:
```tsx
"use client";

import { LineChart, Line, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";

interface Point {
  generatedAt: string;
  score: number;
}

export function ScoreTrendChart({ data }: { data: Point[] }) {
  const chartData = [...data]
    .sort((a, b) => new Date(a.generatedAt).getTime() - new Date(b.generatedAt).getTime())
    .map((point) => ({
      date: new Date(point.generatedAt).toLocaleDateString(undefined, { month: "short", day: "numeric" }),
      score: point.score,
    }));

  return (
    <ResponsiveContainer width="100%" height={220}>
      <LineChart data={chartData} margin={{ top: 8, right: 16, left: -16, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
        <XAxis dataKey="date" tick={{ fontSize: 12 }} stroke="#a3a3a3" />
        <YAxis domain={[0, 100]} tick={{ fontSize: 12 }} stroke="#a3a3a3" />
        <Tooltip />
        <Line type="monotone" dataKey="score" stroke="#2554eb" strokeWidth={2} dot={{ r: 3 }} />
      </LineChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 3: Create the factor-breakdown bar chart**

Create `components/factor-breakdown-chart.tsx`:
```tsx
"use client";

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import type { ScoreFactor } from "@/lib/types";

export function FactorBreakdownChart({ factors }: { factors: ScoreFactor[] }) {
  const chartData = factors.map((f) => ({
    name: f.name.replace(/_/g, " "),
    contribution: f.contribution,
  }));

  return (
    <ResponsiveContainer width="100%" height={220}>
      <BarChart data={chartData} margin={{ top: 8, right: 16, left: -16, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
        <XAxis dataKey="name" tick={{ fontSize: 11 }} stroke="#a3a3a3" />
        <YAxis tick={{ fontSize: 12 }} stroke="#a3a3a3" />
        <Tooltip />
        <Bar dataKey="contribution" fill="#2554eb" radius={[4, 4, 0, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 4: Create the page (Server Component)**

Create `app/(dashboard)/businesses/[id]/page.tsx`:
```tsx
import { getApiKey } from "@/lib/session";
import { createApiClient, ApiError } from "@/lib/api-client";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { ScoreTrendChart } from "@/components/score-trend-chart";
import { FactorBreakdownChart } from "@/components/factor-breakdown-chart";

const CONFIDENCE_TONE = {
  HIGH: "success",
  MEDIUM: "warning",
  LOW: "danger",
} as const;

export default async function BusinessProfilePage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  const apiKey = await getApiKey();
  if (!apiKey) {
    return <ErrorState title="Not signed in" message="Your session expired. Please sign in again." />;
  }

  const client = createApiClient({ apiKey });

  let business;
  try {
    business = await client.getBusiness(id);
  } catch (err) {
    if (err instanceof ApiError && err.status === 404) {
      return <ErrorState title="Business not found" message={`No business exists with id ${id}.`} />;
    }
    const message = err instanceof ApiError ? err.message : "Unexpected error loading this business.";
    return <ErrorState title="Couldn't load business" message={message} />;
  }

  const [scoreResult, historyResult, transactionsResult] = await Promise.allSettled([
    client.getScore(id),
    client.getScoreHistory(id),
    client.getTransactions(id, 0, 10),
  ]);

  const hasScore = scoreResult.status === "fulfilled";
  const history = historyResult.status === "fulfilled" ? historyResult.value : [];
  const transactions = transactionsResult.status === "fulfilled" ? transactionsResult.value.content : [];

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-semibold">{business.name}</h1>
        <p className="text-sm text-neutral-500">
          {business.country} · {business.industry ?? "Unknown industry"} · onboarded {business.onboardingDate}
        </p>
      </div>

      {!hasScore ? (
        <EmptyState
          title="No score yet"
          description="This business hasn't been synced, so no creditworthiness score has been computed."
        />
      ) : (
        <>
          <Card className="flex items-center justify-between">
            <div>
              <p className="text-sm text-neutral-500">Latest score</p>
              <p className="text-3xl font-semibold text-neutral-900">{scoreResult.value.score.toFixed(1)}</p>
              <p className="text-xs text-neutral-400">
                model {scoreResult.value.modelVersion} · generated{" "}
                {new Date(scoreResult.value.generatedAt).toLocaleString()}
              </p>
            </div>
            <Badge tone={CONFIDENCE_TONE[scoreResult.value.confidenceLevel]}>
              {scoreResult.value.confidenceLevel} confidence
            </Badge>
          </Card>

          <Card>
            <h2 className="mb-2 text-sm font-medium text-neutral-700">Score over time</h2>
            {history.length < 2 ? (
              <p className="py-8 text-center text-sm text-neutral-500">
                Not enough score history yet to plot a trend.
              </p>
            ) : (
              <ScoreTrendChart data={history.map((h) => ({ generatedAt: h.generatedAt, score: h.score }))} />
            )}
          </Card>

          <Card>
            <h2 className="mb-2 text-sm font-medium text-neutral-700">Factor breakdown</h2>
            <FactorBreakdownChart factors={scoreResult.value.factors} />
            <ul className="mt-4 space-y-2 text-sm">
              {scoreResult.value.factors.map((factor) => (
                <li key={factor.name} className="text-neutral-600">
                  <span className="font-medium text-neutral-800">{factor.name.replace(/_/g, " ")}:</span>{" "}
                  {factor.explanation}
                </li>
              ))}
            </ul>
          </Card>
        </>
      )}

      <Card>
        <h2 className="mb-2 text-sm font-medium text-neutral-700">Recent transactions</h2>
        {transactions.length === 0 ? (
          <EmptyState title="No transactions" description="No transactions have been synced for this business." />
        ) : (
          <table className="w-full text-sm">
            <thead>
              <tr className="border-b border-neutral-100 text-left text-neutral-500">
                <th className="py-2 font-medium">Date</th>
                <th className="py-2 font-medium">Direction</th>
                <th className="py-2 font-medium">Amount</th>
                <th className="py-2 font-medium">Status</th>
              </tr>
            </thead>
            <tbody>
              {transactions.map((tx) => (
                <tr key={tx.id} className="border-b border-neutral-50">
                  <td className="py-2 text-neutral-700">{new Date(tx.transactionDate).toLocaleDateString()}</td>
                  <td className="py-2 text-neutral-700">{tx.direction}</td>
                  <td className="py-2 text-neutral-700">
                    {tx.amount.toLocaleString()} {tx.currency}
                  </td>
                  <td className="py-2">
                    <Badge tone={tx.status === "COMPLETED" ? "success" : "danger"}>{tx.status}</Badge>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </Card>
    </div>
  );
}
```

Note on `hasScore`: `client.getScore(id)` throws an `ApiError` with `status: 404` when the business has no `ScoreProfile` row yet (matches the backend's `ScoringService.getLatestOrThrow` → `NoSuchElementException` → `ApiExceptionHandler`'s 404 mapping). `Promise.allSettled` is used specifically so that a 404 on `getScore` (an expected, common state — e.g. the seeded Ghana business) doesn't fail the whole page the way `Promise.all` would; `getScoreHistory`/`getTransactions` degrade to empty arrays independently if they fail for any reason.

- [ ] **Step 5: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds.

- [ ] **Step 6: Manual verification**

With the Java backend (seed profile) running and signed in: visit the Kenya or Nigeria business (synced, has a score) via the `/businesses` list — confirm the score, confidence badge, factor bar chart with explanations, and transactions table render. Score-trend line chart: the seed data only produces one `ScoreProfile` row per business, so confirm the "not enough score history yet" message renders instead of an empty/broken chart. Visit the Ghana business (unsynced) — confirm the "No score yet" empty state renders instead of an error. Visit a random UUID that doesn't exist — confirm the "Business not found" state renders (not a raw 500/crash).

- [ ] **Step 7: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add "app/(dashboard)/businesses/[id]" components/score-trend-chart.tsx components/factor-breakdown-chart.tsx package.json package-lock.json
git commit -m "Add business score profile page with trend and factor charts

Fetches business/score/history/transactions server-side via
Promise.allSettled so a 404 on score (unsynced business) degrades to an
explicit 'no score yet' state instead of failing the whole page. Charts
are the only client components on the route; data flows down as props."
```

---

## Task 10: Frontend — API usage overview page

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/app/(dashboard)/usage/page.tsx`
- Create: `/home/sheri/code/credit-scoring-dashboard/components/usage-by-endpoint-chart.tsx`

**Interfaces:**
- Consumes: `getApiKey`, `createApiClient`, `ApiError`, `UsageSummary` (Tasks 5-6), `Card`, `EmptyState`, `ErrorState` (Task 4).
- Produces: `/usage` page.

- [ ] **Step 1: Create the by-endpoint bar chart**

Create `components/usage-by-endpoint-chart.tsx`:
```tsx
"use client";

import { BarChart, Bar, XAxis, YAxis, Tooltip, ResponsiveContainer, CartesianGrid } from "recharts";
import type { UsageEndpointCount } from "@/lib/types";

export function UsageByEndpointChart({ data }: { data: UsageEndpointCount[] }) {
  const chartData = data.map((d) => ({
    endpoint: d.endpoint.replace("/api/v1", ""),
    count: d.count,
  }));

  return (
    <ResponsiveContainer width="100%" height={260}>
      <BarChart data={chartData} layout="vertical" margin={{ top: 8, right: 16, left: 24, bottom: 0 }}>
        <CartesianGrid strokeDasharray="3 3" stroke="#e5e5e5" />
        <XAxis type="number" tick={{ fontSize: 12 }} stroke="#a3a3a3" />
        <YAxis type="category" dataKey="endpoint" tick={{ fontSize: 11 }} width={180} stroke="#a3a3a3" />
        <Tooltip />
        <Bar dataKey="count" fill="#2554eb" radius={[0, 4, 4, 0]} />
      </BarChart>
    </ResponsiveContainer>
  );
}
```

- [ ] **Step 2: Create the page (Server Component)**

Create `app/(dashboard)/usage/page.tsx`:
```tsx
import { getApiKey } from "@/lib/session";
import { createApiClient, ApiError } from "@/lib/api-client";
import { Card } from "@/components/ui/card";
import { EmptyState } from "@/components/ui/empty-state";
import { ErrorState } from "@/components/ui/error-state";
import { UsageByEndpointChart } from "@/components/usage-by-endpoint-chart";

export default async function UsagePage() {
  const apiKey = await getApiKey();
  if (!apiKey) {
    return <ErrorState title="Not signed in" message="Your session expired. Please sign in again." />;
  }

  const client = createApiClient({ apiKey });

  try {
    const summary = await client.getUsageSummary();

    return (
      <div className="space-y-6">
        <div>
          <h1 className="text-2xl font-semibold">API usage</h1>
          <p className="text-sm text-neutral-500">
            Raw call volume for your API key. Usage-based billing isn&apos;t built yet — this is metering, not an
            invoice.
          </p>
        </div>

        <Card>
          <p className="text-sm text-neutral-500">Total calls</p>
          <p className="text-3xl font-semibold text-neutral-900">{summary.totalCalls}</p>
        </Card>

        {summary.byEndpoint.length === 0 ? (
          <EmptyState title="No API calls yet" description="Usage will appear here once you start calling the API." />
        ) : (
          <Card>
            <h2 className="mb-2 text-sm font-medium text-neutral-700">Calls by endpoint</h2>
            <UsageByEndpointChart data={summary.byEndpoint} />
          </Card>
        )}

        <Card>
          <h2 className="mb-2 text-sm font-medium text-neutral-700">Recent calls</h2>
          {summary.recentCalls.length === 0 ? (
            <EmptyState title="No recent calls" />
          ) : (
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b border-neutral-100 text-left text-neutral-500">
                  <th className="py-2 font-medium">Time</th>
                  <th className="py-2 font-medium">Method</th>
                  <th className="py-2 font-medium">Endpoint</th>
                  <th className="py-2 font-medium">Status</th>
                </tr>
              </thead>
              <tbody>
                {summary.recentCalls.map((call, i) => (
                  <tr key={i} className="border-b border-neutral-50">
                    <td className="py-2 text-neutral-700">{new Date(call.calledAt).toLocaleString()}</td>
                    <td className="py-2 text-neutral-700">{call.method}</td>
                    <td className="py-2 text-neutral-700">{call.endpoint}</td>
                    <td className="py-2 text-neutral-700">{call.responseStatus}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>
    );
  } catch (err) {
    const message = err instanceof ApiError ? err.message : "Unexpected error loading usage data.";
    return <ErrorState title="Couldn't load usage" message={message} />;
  }
}
```

- [ ] **Step 3: Verify the build**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build
```
Expected: build succeeds.

- [ ] **Step 4: Manual verification**

Signed in, visit `/usage` — confirm total call count, the by-endpoint bar chart, and the recent-calls table render with real data (there will be plenty of prior calls from all the manual verification in Tasks 8-9). Confirm the "Usage-based billing isn't built yet" framing is visible, per the spec's requirement not to imply billing exists.

- [ ] **Step 5: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add "app/(dashboard)/usage" components/usage-by-endpoint-chart.tsx
git commit -m "Add API usage overview page

Reads GET /usage/summary; explicitly framed as raw usage metering, not
billing, since no pricing logic exists in the backend yet."
```

---

## Task 11: Final end-to-end verification and README

**Files:**
- Create: `/home/sheri/code/credit-scoring-dashboard/README.md`

**Interfaces:**
- None — this task verifies the whole app, no new interfaces.

- [ ] **Step 1: Fresh full-stack verification**

```bash
cd /home/sheri/code/java_project
docker compose down -v
docker compose up -d postgres
SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run > /tmp/backend.log 2>&1 &
```
Wait for `Started PlatformApplication` and capture the logged demo API key (`grep "Demo consumer API key" /tmp/backend.log`).

```bash
cd /home/sheri/code/credit-scoring-dashboard
npm run build && npm run start &
```

- [ ] **Step 2: Walk every screen with a fresh browser session**

1. Visit `http://localhost:3000/` → redirected to `/login`.
2. Enter a wrong key → inline error, stays on `/login`.
3. Enter the real seed key → redirected to `/businesses`, all 3 seeded businesses visible.
4. Search for a substring of one seeded business's name → list filters to matches; search for nonsense → empty state.
5. Open the Kenya or Nigeria business → score, confidence badge, factor chart + explanations, transactions table all render.
6. Open the Ghana business → "No score yet" empty state, not an error.
7. Visit `/businesses/00000000-0000-0000-0000-000000000000` directly → "Business not found" state.
8. Visit `/usage` → total calls, by-endpoint chart, recent calls table all populated (from steps 3-7's own API traffic).
9. Click "Sign out" → redirected to `/login`; visiting `/businesses` again redirects back to `/login` (cookie cleared).

- [ ] **Step 3: Run the full test suite one more time**

```bash
cd /home/sheri/code/credit-scoring-dashboard
npx vitest run
```
Expected: all tests pass.

```bash
cd /home/sheri/code/java_project
./mvnw -q test
```
Expected: all tests pass (17 from the backend MVP, unaffected by this work, plus no new backend tests were added per this plan's stated backend-testing approach).

- [ ] **Step 4: Write the dashboard README**

Create `/home/sheri/code/credit-scoring-dashboard/README.md`:
```markdown
# Credit Scoring Dashboard

Lender-facing dashboard for the SME credit-scoring platform. Next.js (App
Router) + TypeScript + Tailwind, consuming the Java backend's REST API.

## Requirements

- Node.js 20+
- The Java backend running locally (see `../java_project/README.md`) —
  `docker compose up -d postgres` then
  `SPRING_PROFILES_ACTIVE=seed ./mvnw spring-boot:run` from that repo.

## Run it

```bash
npm install
npm run dev
```

Visit `http://localhost:3000`, sign in with the API key the backend logs on
startup (`SeedDataRunner` prints it once).

## Architecture

The API key you enter at login is stored in an `httpOnly` cookie and never
reaches browser JavaScript. Server Components read it and call the Java API
directly (server-to-server — no CORS needed); mutations go through Server
Actions for the same reason. See
`../java_project/docs/superpowers/specs/2026-09-09-lender-dashboard-design.md`
for the full design rationale.

## Tests

```bash
npx vitest run
```

Covers `lib/api-client.ts` — the one module with real branching logic
(error mapping, query-string construction). No broader frontend test suite
in this pass; verify screens manually against the running backend.

## Screens

- `/login` — API key entry
- `/businesses` — search/lookup
- `/businesses/[id]` — score profile (breakdown, history, transactions)
- `/usage` — raw API usage (not billing — no pricing logic exists yet)
```

- [ ] **Step 5: Commit**

```bash
cd /home/sheri/code/credit-scoring-dashboard
git add README.md
git commit -m "Add dashboard README with setup, architecture, and screen overview"
```

- [ ] **Step 6: Stop the background processes started in Step 1**

```bash
pkill -f "spring-boot:run" 2>/dev/null
pkill -f "next start" 2>/dev/null
```

---

## Self-review notes (for the plan author, not a task)

- **Spec coverage:** all four screens (login, search, profile, usage) → Tasks 6, 8, 9, 10. Both backend additions → Tasks 1, 2. Server-first architecture (httpOnly cookie, no CORS) → Task 6. Design system (deep blue, custom spacing/radius/type scale, distinct fonts) → Task 4. Empty/loading/error states → present in every page task (8, 9, 10) plus explicit checks in Task 11's walkthrough. "Not a template" shadcn deviation is called out once in Global Constraints rather than repeated per-task.
- **Type consistency check:** `ApiClient`'s method names (`listBusinesses`, `getBusiness`, `getScore`, `getScoreHistory`, `getTransactions`, `getUsageSummary`, `triggerSync`, `createBusiness`, `createDataSource`) are defined once in Task 5 and referenced identically in Tasks 6, 8, 9, 10 — no renames across tasks. `Business.status`/`ScoreProfile.confidenceLevel` string-union types in `lib/types.ts` (Task 5) match the exact enum values the Java backend serializes (`BusinessStatus`, `ConfidenceLevel` — verified against `BusinessResponse`/`ScoreResponse`/`ConfidenceLevel.java` in this repo).
- **`createBusiness`/`createDataSource`** are defined on the API client (Task 5) but no page task actually calls them — the spec's screens are read-oriented (search, view, usage) and don't include a "register a business from the dashboard" flow. Left in the client since they're trivial and match existing backend endpoints 1:1, but flagging: if a future pass wants an in-dashboard registration flow, the client already supports it.
