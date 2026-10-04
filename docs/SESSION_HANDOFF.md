# Nexus Session Handoff

## Current stage

Registration and Login have completed their current checkpoints. Application create, list, update, and soft-delete endpoints are implemented and verified through real JWT + HTTP + MySQL flow tests. The list test covers two authenticated owners, identical Application names, and exclusion of a soft-deleted row. The next module is API Key, starting with its lifecycle and data model rather than implementation.

## Decisions and constraints

- Management requests identify the user through a verified Bearer JWT; never accept a client-supplied owner ID. API keys will identify machine calls, not human management requests.
- V3's active-only unique key `(owner_user_id, name, active_slot)` allows reuse of a soft-deleted Application name. A disabled but non-deleted Application still occupies its name.
- An API key has its own independent enabled/disabled state. Effective usability requires both the key and its parent Application to be enabled and not deleted. Disabling an Application therefore makes its keys immediately unusable without overwriting each key's independent status; re-enabling it must not revive individually disabled keys.
- When an Application is soft-deleted, its child API keys should also be soft-deleted in the same transaction once the API Key module exists. Authorization must still reject keys whose parent is deleted. Reusing an Application name must not revive old keys.
- No API Key schema or code exists yet. Finalize its fields, secret storage/one-time display, status and deletion semantics, uniqueness, indexes, and ownership rules before writing a Flyway migration.

## Verification and open work

- The new list-flow test passed on its own; the full suite passed 86/86 (0 failures/errors) using a temporary test-only JWT secret. Local JWT secrets remain external configuration.
- Begin API Key design. Derive tests for the important lifecycle transitions: key individually disabled; parent disabled then re-enabled; parent soft-deleted; name reused by a new Application. Decide API Key management API shape and key-secret handling before implementing.
- Code and tests take precedence over this handoff if later changes make it stale.

## Relevant files

- `AGENTS.md`
- `nexus-server/src/main/java/com/nexus/application/controller/ApplicationController.java`
- `nexus-server/src/main/java/com/nexus/application/service/ApplicationServiceImpl.java`
- `nexus-server/src/main/resources/db/migration/V3__change_application.sql`
- `nexus-server/src/test/java/com/nexus/application/flowTest/`
- `nexus-server/src/test/java/com/nexus/application/flowTest/ApplicationListFlowIntegrationTests.java`
- `nexus-server/src/main/java/com/nexus/auth/web/BearerUserIdResolver.java`
