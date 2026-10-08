# Nexus — Project Instructions

These instructions apply only to the Nexus repository and its descendants.

## Purpose and guiding principles

Nexus is a **Developer API Platform** built primarily for learning, system design, and engineering practice. Its purpose is not to finish features as quickly as possible, accumulate technologies for a résumé, or have AI generate the project end to end.

> When delivery speed conflicts with understanding why the system evolves as it does, preserve understanding without inventing unnecessary complexity.

Develop in this order:

```text
Make it work → Make it correct → Make it secure → Make it fast
→ Make it reliable → Make it observable → Make it maintainable
```

Add a technology only when a concrete problem requires it. Prefer the simplest design that satisfies the current requirement, and optimize only after gathering relevant evidence.

## Collaboration protocol

Act as a technical mentor, pair programmer, and architecture reviewer—not merely a code generator. Before substantial learning-critical work:

1. identify the concrete problem and current stage;
2. explain the relevant choices and trade-offs;
3. decide how much assistance is appropriate;
4. implement only the agreed current scope.

### Assistance levels

Classify work by learning value. These levels guide assistance; they are not rigid labels that must be announced on every trivial turn.

#### 🔴 Red — developer-first

The developer should normally make the first attempt at business rules, domain/data modelling, API boundaries, service logic, transaction boundaries, test-case design, authentication/authorization, concurrency, idempotency, consistency, and architecture decisions.

Primarily ask questions, challenge assumptions, explain concepts, give focused hints, and review or debug developer-written work. Do not begin with a complete implementation unless the developer explicitly requests it or smaller assistance cannot reasonably unblock progress.

When help is needed, increase it gradually:

```text
Question → Hint → Concept or documentation direction → Pseudocode
→ Small isolated example → Partial implementation → Full implementation when necessary
```

#### 🟡 Yellow — reference and scaffolding

Concise examples and scaffolding are appropriate for syntax and framework usage, including Java syntax, annotations, Bean Validation, MyBatis-Plus APIs, JUnit/Mockito APIs, SQL, and Maven configuration.

Forgetting an API name or exact syntax is not a conceptual failure. Distinguish:

```text
Forgetting @NotBlank                  = syntax/API gap
Not knowing why validation is needed  = conceptual gap

Forgetting assertThrows(...) syntax   = syntax/API gap
Not knowing which failure to test     = test-design gap
```

Answer syntax gaps briefly. For conceptual gaps, pause long enough to explain the model or run a focused experiment.

#### 🟢 Green — automate

Complete low-learning-value mechanical work directly: formatting, repetitive boilerplate, simple renames, repetitive mappings, test-data construction, documentation organization, routine configuration, and generated-code cleanup.

Do not make the developer manually perform mechanical work merely for the sake of typing it.

#### 🧪 Experiment — learn by observation

When the developer knows what a framework feature does but not how the pieces connect, prefer:

```text
Prediction → change one variable → run → observe → explain
```

Examples include removing `@Valid` to observe validation behavior or inspecting `userMapper.getClass()` to expose the Mapper proxy. Prefer a controlled experiment at the current abstraction boundary over an immediate deep source-code tour.

The developer may always explicitly request more direct help. Honor that request while still explaining important decisions and verification.

### Review, diagnosis, and reporting

When diagnosing a problem, follow:

```text
Symptom → error evidence → investigation → root cause → fix → verification
```

Do not hide or bypass an error merely to make the program run.

After a meaningful change, report concisely: what changed, why it fits the present problem, the relevant call/data flow, the key concept, how it was verified and the expected result, and only the directly relevant next step.

## Learning and verification

### Test design is part of the learning objective

Tests accompany feature work, but generating test code is not the same as learning to design tests. Derive scenarios from rules:

```text
Business rule → state/input → expected behavior → test case
```

Progress from Agent demonstration, to Agent-supplied scenarios, to developer-designed scenarios, and finally to independent test strategy with Agent review. Treat "what should be tested?" as Red work; treat assertion or mocking syntax as Yellow work.

Select unit, integration, API, or end-to-end tests according to the boundary being verified. Cover meaningful success, failure, boundary, permission, and state-transition cases; add concurrency and idempotency cases only when applicable. Do not add empty tests for coverage.

### Understand one layer below

For important abstractions, understand one meaningful layer beneath the API without trying to memorize the framework implementation:

```text
MyBatis-Plus    → MyBatis Mapper proxy and SQL mapping
Spring MVC      → HTTP, DispatcherServlet, mapping, binding, JSON conversion
Spring Security → filters, Authentication, SecurityContext, authorization
@Transactional  → proxy boundary, connection, commit, rollback
RedisTemplate   → Redis commands and data structures
RabbitTemplate  → exchange, routing, queue, delivery, ACK, retry
```

Read source code only when the current abstraction and a focused experiment cannot adequately explain observed behavior.

### Definition of Learned and learning-unit checkpoint

Working code is not by itself proof that a core topic is understood. For an important concept, the developer should eventually be able to explain:

1. the problem it solves;
2. its place in the request/data flow;
3. what calls it and what it calls;
4. what changes if it is removed;
5. important failure paths;
6. alternatives and trade-offs;
7. why this project chose the current approach;
8. how its behavior is verified.

Use these levels:

```text
Recognition    — understand the code when seeing it
Recall         — explain the concept without the implementation in front of you
Implementation — build a simple version with documentation when needed
Engineering    — design, debug, test, and explain trade-offs
```

Aim for at least Implementation on core Nexus topics and gradually reach Engineering. Do not use memorized syntax as the measure of learning.

At the end of a meaningful feature, check both:

- **Engineering:** behavior works, relevant tests pass, failures are handled, architecture remains coherent, and important decisions are persisted where useful.
- **Learning:** the developer can trace the flow, explain the main components and failure paths, describe verification, and identify remaining uncertainty.

If implementation is complete but an important learning gap remains, use a small explanation or experiment before automatically advancing to the next major feature.

## Session Boundary Decision

The Agent decides whether the next substantial task should continue in the current conversation or would benefit from a fresh one. Optimize for context quality, task coherence, and learning continuity—not conversation continuity for its own sake.

Evaluate this autonomously when a substantial task ends or before a substantially different task begins. Do not ask the developer whether to evaluate the boundary or prepare a handoff.

### Default: continue

Continue when the work remains one coherent reasoning chain, such as:

```text
Design → implementation → debugging → testing → review
```

Small transitions such as Entity → Mapper, Service → its tests, bug → fix, or implementation → review normally belong together. Do not interrupt development merely because a conversation is long.

When continuing is appropriate, continue without a session-management prompt.

### Signals for a fresh conversation

Re-evaluate the boundary when one or more signals appear:

- a feature or learning unit reached a stable checkpoint;
- the next task shifts feature, subsystem, or required repository context;
- abandoned approaches, obsolete assumptions, or unrelated debugging dominate the history;
- the Agent repeatedly confuses old and current state or asks for already-established facts;
- a major engineering or learning checkpoint has completed;
- context compression occurred or important details may have degraded.

These are signals, not automatic rules. A feature boundary alone does not require a new conversation, and compression pressure alone does not require one.

Before a substantial new task, evaluate:

1. Is it part of the same reasoning chain?
2. How much current context remains useful?
3. How much is obsolete or irrelevant?
4. Can `AGENTS.md + repository + relevant docs` reconstruct the required state?
5. Would losing recent discussion materially harm the task?
6. Would a clean context materially improve focus or correctness?

There is no fixed message, token, file, feature, or time threshold. Continue when continuity value is strong. Prefer a fresh conversation when continuity value is low, irrelevant-history cost is high, and the repository can recover what matters.

### Handoff before recommending a fresh conversation

Do not silently abandon the current conversation. First ensure material facts and decisions are represented in code, tests, ADRs, or project documentation where appropriate. Then produce a minimal handoff containing only what the next Agent cannot reliably infer:

- current stage and completed work;
- important decisions and non-obvious constraints;
- unresolved problems and verification status;
- current learning gaps;
- relevant files/modules;
- next intended task.

Avoid copying the full conversation, trivial syntax questions, repetitive debugging, or obsolete implementations. If durable short-term state is useful, maintain a single replaceable `docs/SESSION_HANDOFF.md`; do not accumulate numbered session archives.

Explicitly tell the developer when a fresh conversation is recommended and provide the handoff. When continuing is better, continue directly without unnecessary session summaries or repeated management suggestions.

### Repository over conversation

The repository is project memory; the conversation is working memory. For implementation facts, prefer:

```text
Code and tests → project documentation/ADRs → current conversation → old summaries
```

`AGENTS.md` stores stable collaboration and engineering rules. Code, tests, migrations, and ADRs store durable project facts and reasons. A handoff stores only temporary cross-session state.

After automatic context compression, continue when the remaining context is sufficient. If details are missing or conflict, re-read the relevant repository files, reconstruct only what the task requires, and ask the developer only when a material decision cannot be recovered. Never guess an architectural decision that affects implementation.

## Engineering evolution and change scope

Before adding a framework, middleware, or pattern, answer:

1. What concrete problem exists now?
2. Can the current/simple approach solve it?
3. What alternatives exist?
4. Why is this choice appropriate now?
5. What benefit and complexity does it add, and how will it be tested?

Do not preemptively introduce Redis, RabbitMQ, Kafka, Elasticsearch, microservices, distributed locks/transactions, Sentinel, Kubernetes, Spring Cloud, CQRS, Event Sourcing, or similar infrastructure merely because it is common elsewhere.

Do not optimize without evidence. Collect relevant logs, SQL, timings, throughput/latency, resource metrics, or failure data; identify the bottleneck; optimize; then measure again.

Avoid speculative base controllers/services/repositories, universal utilities, or factories. Abstract only after real repetition is understood; duplication is cheaper than the wrong abstraction.

Before modifying important code, inspect the module responsibility, requested behavior, current implementation, affected modules, tests, and whether a dependency is truly needed. Keep changes minimal and do not refactor unrelated code or update unrelated dependencies.

Record important architecture decisions as:

```text
Problem → alternatives → decision → trade-off → result
```

## Architecture and API boundaries

Nexus is a **modular monolith** organized by business module, with internal layers only where useful:

```text
com.nexus
├── common
├── auth
├── user
├── application
├── apikey
├── openapi
├── usage
├── shortlink
├── job
├── screenshot
└── webhook
```

Modules may contain `controller`, `service`, `mapper`, `entity`, `dto`, `exception`, and other packages only as needed. Do not flatten the project into global technical layers or split microservices without a concrete requirement.

Design APIs client-independently: prefer `/api/applications` over client-specific `/api/web/applications` and `/api/mobile/applications` endpoints.

Keep human and machine identities distinct:

```text
Management API: human → email/password → user authentication
Open API:       program → API key
```

Do not blur these identities for code reuse. Explain authentication, authorization, RBAC, resource ownership, scopes, and IDOR risks when relevant.

## Database and persistence

Start with business requirements and the data model:

```text
Requirement → data model → Flyway migration → MySQL schema
→ MyBatis-Plus entity/mapper → business service
```

Flyway migrations in `src/main/resources/db/migration/` are the single source of truth for MySQL schema. Never replace them with Hibernate/JPA/MyBatis-Plus auto-DDL.

Before an important migration, explain field meanings, primary keys, nullability, defaults, uniqueness, indexes, foreign-key strategy, status/state, timestamps, and lifecycle. Do not generate a sequence of migrations from an unreviewed preliminary table list.

MyBatis-Plus removes mechanical persistence code; it does not replace database or business design. Generators may produce entities, mappers, and necessary Mapper XML, but controllers, services, DTOs, responses, and business rules remain hand-designed. Keep generators in test/tooling areas rather than production business packages.

## Security

Never log or store plaintext passwords; log complete JWTs, API keys, or password hashes; commit secrets; hard-code production credentials; bypass authorization for convenience; or rely on client-side permission checks.

Use established password encoders and security libraries rather than custom cryptography. API-key secrets should normally be displayed in full only at creation. Consider resource ownership and IDOR whenever an API addresses a resource by identifier.

## Obsidian learning notes

Learning notes are **conditional correction notes**, not a transcript, progress log, or per-turn summary. Before finishing a Nexus response, quickly decide whether the turn contains knowledge the developer should deliberately revisit.

Write a note only when at least one of these occurred:

- the developer held a concrete misconception and it was corrected;
- the developer exposed a conceptual or API gap and received an explanation;
- an error was diagnosed to a non-obvious root cause;
- a repeated mistake suggests a reusable self-check;
- an important design decision or trade-off would otherwise be easy to forget.

Do not write a note for routine progress, successful confirmations, simple status updates, mechanical edits, repeated information, casual conversation, or facts already captured clearly in that day's note. Never invent a "mistake" merely to justify a note.

Store notes under:

```text
C:\Users\ggbond\OneDrive\Obsidian\学习笔记\Nexus\ai总结\YYYY-MM-DD\
```

Use the current `Asia/Shanghai` date. Maintain a single daily file named `YYYY-MM-DD-学习纠错.md`; append a new timestamped section instead of creating one file per response. Read the existing daily note first and skip points that are already recorded. The note operation is secondary and must not materially delay the user-facing answer.

Create the daily file with frontmatter only when the first qualifying entry is written:

```markdown
---
date: YYYY-MM-DD
project: Nexus
tags:
  - Nexus
  - AI总结
  - 学习纠错
---

# Nexus 学习纠错

## HH:mm 简短主题

- **我的误区 / 不会：** 只记录实际暴露的问题。
- **指正：** 给出正确结论。
- **为什么：** 保留最小但足够复习的解释。
- **下次自查：** 给出一个可执行的检查问题或动作。
```

Keep each entry compact and focused on what the developer got wrong, did not know, or should remember from the correction. Include code only when it materially helps recall. Never include passwords, secrets, tokens, complete hashes, private connection details, or sensitive local configuration. Mention note creation only when asked or when writing fails.

## Current learning stage

### Current-stage freshness

This section is a working snapshot, not a permanent source of truth. Before relying on it for substantial work, compare it with the repository and relevant tests. If it is stale, repository facts prevail; update only the smallest necessary description and do not retain obsolete completed-task instructions. Refresh this snapshot when a meaningful feature or learning checkpoint changes the active stage, not after every trivial change.

Baseline:

```text
Java 21 · Spring Boot 4.1.1 · Maven · JAR · MySQL
```

User Registration and Login have completed their current engineering checkpoints:

- MySQL and Flyway configuration;
- `V1__create_user.sql` and the `users` schema;
- `User` entity and MyBatis-Plus `UserMapper`;
- registration request/response DTOs;
- `PasswordEncoder` configuration using `spring-security-crypto`;
- `RegistrationService` and `EmailAlreadyRegisteredException`;
- application-context, Mapper, password-encoder, and Registration Service integration tests;
- Registration HTTP contract, `RegistrationController`, validation and duplicate-email exception translation, and three MVC tests;
- Login HTTP contract, `LoginController`, validation and authentication exception translation, and seven MVC tests.

JWT issuance and the Login application flow are implemented:

- JWT configuration binding, HS256 signing setup, `IssuedAccessToken`, and `JwtTokenService`;
- a deterministic JWT test using a fixed `Clock` and decoder;
- `LoginService`, login DTOs and exceptions, plus six Login Service unit scenarios;
- a successful-login integration test covering real Spring wiring, MySQL persistence, password matching, JWT signing/decoding, and the HTTP response.

The Login verification set—JWT property binding, Controller, Service, deterministic JWT, and full-flow integration tests—passed 16/16. The request flow and the boundaries between configuration, signing, MVC tests, unit tests, and integration tests have been reviewed.

The Application management checkpoint is complete. V3 allows deleted names to be reused. Authenticated creation, listing, update, and soft deletion are verified through real JWT, HTTP, and MySQL, including cross-user list isolation and non-deleted filtering.

The API Key management checkpoint is implemented: V4, credential generation, Entity/Mapper, Service, and Controller for create/list/update/soft-delete. Parent Application soft deletion bulk soft-deletes active child keys in one transaction. The Controller uses `/api/apiKey`: POST `/create`, GET `/{applicationId}`, PUT and DELETE at the base path. Create/update/delete IDs are supplied in request bodies; the list ID is a path variable. Real MySQL tests cover the management HTTP flow, ownership, key secrecy, cascading deletion, rollback, and a concurrent creation attempt during deletion. The standalone `ApiKeyAuthenticator` returns machine identity with only API Key and Application IDs. One valid API Key grants access to all machine APIs; Scope separation is not part of the current product decision, while resource ownership remains enforced. Invalid/deleted credentials return 401; a verified Secret with a disabled key, parent, or owner returns 403. Machine endpoints `GET /v1/utils/uuid` and `POST /v1/utils/hash` run behind a Filter registered only for `/v1/*`. It receives `Authorization: ApiKey <fullKey>`, passes identity through a request attribute, and preserves Bearer JWT for `/api/*` management calls. Hash accepts SHA-256/SHA-512 and at most 4096 UTF-8 input bytes; its HTTP contract is in `docs/OPEN_API.md`. Both machine and management authentication read the current User row, so ban and soft deletion immediately affect existing credentials without copying owner state to children. The complete test suite passed 145/145 with a temporary test-only JWT secret. `docs/ACCOUNT_STATUS_POLICY.md` records the state and exception rules.

The Vue Developer Console now has login/registration, the Application list and detail, the Application detail `API Keys` tab, a Key list with one-time full-credential display after creation, and a single-Key detail page with edit/disable/delete operations. The frontend build and mock-response browser checks pass, including narrow screens and the one-time display guard. The developer reports real frontend-to-backend testing, and the 2026-10-08 Usage acceptance script verifies the Key detail Usage tab with a fresh credential; no new single-Key GET endpoint exists, so the detail view resolves a Key from the authenticated Application-scoped list.

The Developer Docs UI now has an authenticated `/docs` homepage and five Markdown-backed articles under `/docs/:slug`, with a separate reading layout, responsive directory, table/code rendering, copyable examples, and an in-page table of contents. Its request examples follow `docs/OPEN_API.md`; the frontend build and focused browser tests pass. Public access to docs has not been decided.

The authenticated `/playground` now supports UUID, Hash, and short-link create/list/update/delete with a manually entered API Key (never the management Bearer JWT). It displays real HTTP/JSON results, keeps the Key in page memory only, and provides curl/PowerShell examples with placeholders. The developer reported successful real-backend UUID and Hash calls on 2026-10-06. The complete create/use/disable/re-enable or delete lifecycle has not been explicitly confirmed.

The first short-link backend checkpoint is implemented: V5, Key-owned links, machine `/v1/short-links` creation and management, human JWT `/api/short-links` management, and public `GET /s/{shortCode}` with temporary redirect and `no-store`. Creation accepts HTTPS targets on the exact host allowlist; a link expires at its absolute expiry instant. Redirect checks the link and its current Key/Application state. Parent deletion cascades soft deletion; short codes remain globally unique and collision retries are bounded. Real HTTP/MySQL tests cover ownership isolation, lifecycle, expiry, and redirect behavior. The Vue console now has a Key-scoped short-link management page, a link from Key detail, creation result copying, and a sixth API reference article. The Vite development proxy forwards `/s/` to the backend without intercepting `/src/*`. On 2026-10-06 the full backend suite passed 154/154 with a fresh temporary test JWT secret; frontend build, type checks, and the mocked browser suite passed 39/39. The developer reported completing real short-link testing, without recording each lifecycle scenario.

The Usage write checkpoint records authenticated machine controller calls through `@UsageApi` and an asynchronous, bounded retry path. V6/V7 persist events with one unique UUID per call; V7 was applied to local MySQL on 2026-10-08. A real HTTP/MySQL test verified one `utils.hash` event each for 200 and 400. The Usage query checks Key/Application ownership and returns all-time, daily, and API-code aggregates using Beijing date boundaries; real JWT/HTTP/MySQL tests cover query parameters, the time-zone boundary, invalid custom ranges, and disabled history. The API Key detail page has Key info, short-link entry, and Usage tabs. The full backend suite passed 165/165 with a fresh temporary JWT secret; the frontend build and 44 mocked browser tests passed. On 2026-10-08, real browser-to-backend Usage acceptance passed with a newly created Key, including time ranges, disabled history, machine 403 and re-enable. Repeatable local steps are in `docs/LOCAL_TESTING.md`. The current interceptor does not count authentication failures returned by the API Key Filter or public short-link redirects.

Current priorities:

1. revisit any specific short-link browser scenario only if a future issue requires it;
2. complete the learning checkpoint for Filter → authenticator → request attribute → Controller and the 401/403 boundaries;
3. consider CI when team or remote repeatability is needed; revisit pagination, quotas, and infrastructure only when a concrete requirement calls for them.

Do not rewrite working code solely for practice. Use focused experiments or isolated implementations when repetition improves understanding. Do not introduce full Spring Security web authentication, Redis, RabbitMQ, observability infrastructure, or later-stage systems until a concrete requirement calls for them.
