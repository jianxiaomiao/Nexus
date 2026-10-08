# Nexus Session Handoff

## Current checkpoint

Registration/Login, Application and API Key management, UUID/Hash machine APIs, and the first short-link backend are implemented. Management uses human Bearer JWT; `/v1/*` uses `Authorization: ApiKey <fullKey>`. The Vue console has Application/Key screens, Key-scoped short-link management, six Markdown-backed developer docs, and a playground for UUID/Hash and all four machine short-link operations. The developer confirmed real-backend UUID and Hash calls on 2026-10-06; do not repeat or persist the test credential.

Usage recording marks authenticated machine controller methods with `@UsageApi` and asynchronously writes `usage_events` with bounded retries and one stable event UUID per request. V7 added the unique event ID to the local MySQL schema on 2026-10-08. A real HTTP/MySQL test verified one `utils.hash` event for HTTP 200 and one for HTTP 400. The management query checks Application/Key ownership and returns all-time, daily, and API-code aggregates for an `Asia/Shanghai` range; disabled parents remain readable. `GET /api/usage` now uses query parameters, with real JWT/HTTP/MySQL tests for date boundaries, zero days, invalid custom ranges, and disabled history. The API Key detail page has Key info, short-link entry, and Usage tabs. The full backend suite passed 165/165 using a fresh temporary JWT secret; the frontend build and mocked browser suite passed 44/44. On 2026-10-08, `nexus-console/scripts/accept-usage-real.mjs` passed against running Vite/Spring/MySQL with a newly created Key: UUID usage appeared in the browser, today/custom ranges worked, disabled history stayed visible while machine calls returned 403, and re-enable allowed calls again. The test Application was soft-deleted. Repeatable setup and commands are in `docs/LOCAL_TESTING.md`.

Usage individual history is available at `GET /api/usage/events` with Bearer JWT and required Application/Key IDs. MyBatis-Plus pagination defaults to 10 and accepts size 1–100; the page displays `apiCode`, `httpStatusCode`, `durationMs`, `occurredAt`, with deterministic newest-first order. A real MySQL/HTTP test proves 11 records span two pages and disabled history remains readable. The prior real-browser acceptance script verifies summary and disabled history but not 11-record paging.

The existing Application, API Key, and short-link JWT management list queries now paginate (default 10, size 1–100) and return `{total,current,size,records}`. Their Vue lists paginate; detail views use optional ID filters on the same list queries so items beyond the first page remain directly addressable. The machine short-link list is unchanged. Real MySQL/HTTP tests prove 11 rows across pages and isolation; the full backend suite previously passed 167/167 with a fresh JWT test secret and 2-connection test pool. `docs/MANAGEMENT_PAGINATION.md` holds the new contract. Business-page native controls in Playground, Usage, and short-link management were replaced with Element Plus; the two layout components' navigation/overlay buttons remain exempt. Console, docs, auth, and nested business/document scroll regions now use Element Plus Scrollbar. The Console and Docs shells are viewport-height; their content regions scroll independently and reset on child path changes. Markdown rendering separates code/table fragments so they can use Scrollbar without losing copying or TOC anchors. `docs/ELEMENT_PLUS_AUDIT.md` records the UI choices and date-time semantics. Frontend build, E2E type check, and 56/56 mocked Playwright tests passed, including desktop/mobile scrolling, docs anchors, narrow date-popover bounds, and custom-expiry conversion. Real backend acceptance was not rerun for these UI-only changes.

Short links belong to the creating Key. `/v1/short-links` provides machine creation/list/update/delete, `/api/short-links` provides JWT management, and public `/s/{shortCode}` redirects without a credential. The API contract is in `docs/OPEN_API.md`; V5 and the short-link code/tests are under `nexus-server`. A full backend test run passed 154/154 with a newly generated temporary JWT secret. The frontend build, type checks, and mocked Playwright suite passed 39/39. On 2026-10-06 the developer reported completing short-link testing against the real backend; the individual lifecycle scenarios and results were not recorded.

## Decisions worth carrying forward

- A valid API Key grants access to all `/v1/*` machine APIs; there are no Scopes. This keeps one credential model for tools and short links, at the cost of no per-API restriction. Key ownership of individual short links and independent Key disable/delete still apply.
- A different Key in the same Application cannot view or change a link. JWT management checks the owning user and the selected Key. Full Key credentials are shown only at creation.
- Public redirect uses 302 and `Cache-Control: no-store`; it checks link, Key, and Application state. Parent disablement does not rewrite link status; parent deletion soft-deletes links. Deleted short codes cannot be reused.
- Creation accepts HTTPS and exact allowlisted hostnames only. This validates protocol and host, not destination content. Expiry is an absolute instant and equality means expired. Generated code collisions receive bounded retries.
- Docs are login-gated; whether they should become public is undecided. The playground does not persist a Key.

## Next task and open checks

- Before rerunning integration tests, check that the local datasource points to a disposable, dedicated test database. On 2026-10-08 a new full-suite run was interrupted after its Flyway log showed the local configuration pointed to `nexus` rather than the documented `nexus_test`; this attempt is not a passing run and may have made test writes before interruption. No manual database cleanup was performed. Keep the prior 167/167 result distinct from this stopped attempt.
- Once a safe test datasource is configured, rerun the backend suite and, if useful for release confidence, exercise the 11th-row management pagination through a real browser/backend connection. The existing mocked browser checks and real MySQL/HTTP tests already cover that behavior separately.
- The developer reported real frontend/backend short-link testing. Existing mocked-browser and real HTTP/MySQL tests cover the specified behaviors; do not repeat the lifecycle merely to produce a manual checklist. If a future issue depends on a particular real-browser scenario, verify only that gap. The console builds short URLs from a configured public origin plus `shortCode`; local Vite proxies only `/s/` to the backend.
- The real Usage acceptance script now verifies the Key create/use/disable 403/re-enable path for its fresh test Key. The developer's explanation of Filter → authenticator → request attribute → Controller remains a learning checkpoint. CI and extra infrastructure await concrete requirements.
- UI control unification is complete within the agreed scope. Usage custom range remains Beijing wall time; Playground expiry remains browser-local time converted to an instant. `ConsoleLayout.vue` and `DocsLayout.vue` intentionally keep their navigation/overlay buttons. Verify against a running backend only if a later regression points to this boundary.

## Relevant files

- `AGENTS.md`, `docs/LEARNING_ROADMAP.md`, `docs/OPEN_API.md`, `docs/ACCOUNT_STATUS_POLICY.md`
- `nexus-server/src/main/java/com/nexus/shortlink/`, `nexus-server/src/main/java/com/nexus/openapi/`
- `nexus-server/src/test/java/com/nexus/shortlink/`
- `nexus-console/src/views/ApiPlaygroundView.vue`, `nexus-console/src/api/openApi.ts`, `nexus-console/e2e/playground.spec.ts`
- `nexus-console/src/views/ShortLinksView.vue`, `nexus-console/src/api/shortLinks.ts`, `nexus-console/src/docs/content/shortlink.md`, `nexus-console/e2e/shortLinks.spec.ts`
- `nexus-console/src/views/ApiKeyDetailView.vue`, `nexus-console/src/views/ApiKeyUsagePanel.vue`, `nexus-console/src/api/usage.ts`, `nexus-console/e2e/usage.spec.ts`
- `nexus-console/scripts/accept-usage-real.mjs`, `docs/LOCAL_TESTING.md`
- `nexus-server/src/main/java/com/nexus/usage/`, `nexus-server/src/test/java/com/nexus/usage/`

Inspect `git status` before further edits; preserve any future uncommitted work.
