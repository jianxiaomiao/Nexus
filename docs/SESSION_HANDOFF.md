# Nexus Session Handoff

## Current checkpoint

Registration/Login, Application and API Key management, UUID/Hash machine APIs, and the first short-link backend are implemented. Management uses human Bearer JWT; `/v1/*` uses `Authorization: ApiKey <fullKey>`. The Vue console has Application/Key screens, five Markdown-backed developer docs, and a UUID/Hash playground. The developer confirmed real-backend UUID and Hash calls on 2026-10-06; do not repeat or persist the test credential.

Short links belong to the creating Key. `/v1/short-links` provides machine creation/list/update/delete, `/api/short-links` provides JWT management, and public `/s/{shortCode}` redirects without a credential. The API contract is in `docs/OPEN_API.md`; V5 and the short-link code/tests are under `nexus-server`. A full backend test run passed 154/154 with a newly generated temporary JWT secret. The frontend build, type check, and mocked Playwright suite passed 35/35. These checks do not replace a real browser-to-backend short-link acceptance run.

## Decisions worth carrying forward

- A different Key in the same Application cannot view or change a link. JWT management checks the owning user and the selected Key. Full Key credentials are shown only at creation.
- Public redirect uses 302 and `Cache-Control: no-store`; it checks link, Key, and Application state. Parent disablement does not rewrite link status; parent deletion soft-deletes links. Deleted short codes cannot be reused.
- Creation accepts HTTPS and exact allowlisted hostnames only. This validates protocol and host, not destination content. Expiry is an absolute instant and equality means expired. Generated code collisions receive bounded retries.
- Docs are login-gated; whether they should become public is undecided. The playground does not persist a Key.

## Next task and open checks

- Add short-link creation, Key-scoped listing, rename, enable/disable, and deletion to the Vue console. Keep machine creation under `/v1/*` with an entered API Key and human management under `/api/*` with JWT. Verify public redirect and lifecycle against the real backend.
- The developer has not explicitly confirmed the complete real Key lifecycle: create, use, disable and observe 403, then re-enable or delete. Do not infer this from the UUID/Hash success.
- Repeatable local test setup and the developer's explanation of Filter → authenticator → request attribute → Controller remain learning checkpoints. Scope, usage statistics, and extra infrastructure await concrete requirements.

## Relevant files

- `AGENTS.md`, `docs/LEARNING_ROADMAP.md`, `docs/OPEN_API.md`, `docs/ACCOUNT_STATUS_POLICY.md`
- `nexus-server/src/main/java/com/nexus/shortlink/`, `nexus-server/src/main/java/com/nexus/openapi/`
- `nexus-server/src/test/java/com/nexus/shortlink/`
- `nexus-console/src/views/ApiPlaygroundView.vue`, `nexus-console/src/api/openApi.ts`, `nexus-console/e2e/playground.spec.ts`

Inspect `git status` before further edits; preserve any future uncommitted work.
