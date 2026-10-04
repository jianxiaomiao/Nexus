# Nexus Session Handoff

## Current stage

Registration, Login, Application management, and API Key management have completed their current checkpoints. API Key V4 schema, credential generation, Entity/Mapper, DTOs, Service, and Controller exist. Application deletion bulk soft-deletes active child keys in the same transaction. Machine Open APIs `GET /v1/utils/uuid` and `POST /v1/utils/hash` are wired through an API Key Filter and request-scoped machine identity. A Vue/TypeScript scaffold now exists in `nexus-console/`; the management UI has not been implemented.

## Decisions and constraints

- Management endpoints use a verified human Bearer JWT. Machine calls will use API keys through a separate authentication path.
- A generated key has an independent random public ID and Secret. The full key appears only in the creation response; persistence keeps the Secret SHA-256 digest and a masked preview. List and update responses expose neither the full key nor its digest.
- Key and parent Application have independent disabled states. Effective machine use must check both, as well as soft deletion. A disabled parent does not prevent its owner from listing, updating, or deleting keys; key creation currently rejects a disabled parent.
- Application deletion first updates the parent row and then bulk soft-deletes its active keys in one transaction. Key create/update/delete lock the parent row, coordinating concurrent operations. Deleted key names can be reused.
- API Key management Controller is implemented at `/api/apiKey`: POST `/create`, GET `/{applicationId}`, PUT and DELETE at the base path. Create/update/delete IDs are in request bodies; list uses a path ID. The developer changed the earlier path-ID decision to request-body IDs.
- `ApiKeyAuthenticator.authenticate(fullKey)` returns `ApiKeyIdentity(apiKeyId, applicationId)` only after public-ID lookup, Secret digest verification, and both key/parent state checks. Missing, malformed, unknown, wrong-Secret, deleted-key, and missing/deleted-parent credentials map to 401. A verified Secret with a disabled key or parent maps to 403. The 401 response includes an `ApiKey` challenge. Code and tests are authoritative if this short-term handoff becomes stale.
- Both authentication paths now read the current User row on every request: an existing JWT is checked in `BearerUserIdResolver`; a valid Key follows Application.ownerUserId in `ApiKeyAuthenticator`. Missing/deleted owner returns 401; banned owner returns 403. Key/Application/User deletion takes precedence over disabled state after Secret verification. No owner state is copied into child tables; `docs/ACCOUNT_STATUS_POLICY.md` records this choice and its read cost.
- Only `/v1/*` uses the machine Filter. It accepts `Authorization: ApiKey <fullKey>`, writes 401/403 itself, stores `ApiKeyIdentity` in a request attribute, and then passes control to Spring MVC. Both utility Controllers require this attribute. Existing `/api/*` management endpoints still require human Bearer JWT. Hash accepts `SHA256` or `SHA512` and at most 4096 UTF-8 input bytes; `docs/OPEN_API.md` records its HTTP contract.

## Verification and next work

- The full test suite passed 145/145 with a temporary test-only JWT secret. MVC and MySQL flow tests cover management routes, validation, ownership, ID mixing, key secrecy, parent-to-key cascade, rollback, and key creation attempted while parent deletion is uncommitted. Real HTTP tests also cover owner ban/recovery/deletion, Hash input/error boundaries, and blocking Key creation with a stale JWT.
- The Controller uses distinct POST, GET, PUT, and DELETE mappings. Creation returns the full key once; list/update do not expose it or the Secret hash.
- Review the request flow with the developer as a learning checkpoint: Filter registration → header parsing → authenticator → request attribute → resolver → utility Controller. `docs/OPEN_API.md` contains the client calls and error contract.
- `docs/LEARNING_ROADMAP.md` records the learning-first order. The next active task is the smallest Vue Developer Console path: login → Application → API Key creation and one-time display. The scaffold has a lockfile, and `npm run build` passed using installed dependencies. Afterwards document repeatable tests and begin MySQL-only short links when ready.

## Relevant files

- `AGENTS.md`
- `nexus-server/src/main/java/com/nexus/apikey/`
- `nexus-server/src/main/java/com/nexus/auth/ApiKeyAuthenticator/`
- `nexus-server/src/main/java/com/nexus/openapi/`
- `nexus-console/`
- `nexus-server/src/test/java/com/nexus/openapi/OpenApiHttpIntegrationTests.java`
- `docs/OPEN_API.md`
- `docs/LEARNING_ROADMAP.md`
- `docs/ACCOUNT_STATUS_POLICY.md`
- `nexus-server/src/main/java/com/nexus/application/service/ApplicationService.java`
- `nexus-server/src/main/resources/db/migration/V4__create_apiKey.sql`
- `nexus-server/src/test/java/com/nexus/application/flowTest/ApplicationDeletionFlowIntegrationTests.java`
- `nexus-server/src/test/java/com/nexus/application/service/ApplicationDeletionRollbackIntegrationTests.java`
