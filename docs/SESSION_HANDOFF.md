# Nexus Session Handoff

## Current stage

User Registration and Login have completed their current engineering checkpoints. Authenticated Application creation is verified end to end. The next active slice is listing the current user's Applications; Service and persistence work is present, but the GET HTTP endpoint is not yet implemented.

## Completed work

- MySQL/Flyway user schema, `User`, `UserMapper`, registration DTOs, password encoding, and `RegistrationService`.
- Registration HTTP contract, `RegistrationController`, validation error aggregation, duplicate-email exception translation, and three MVC tests.
- JWT configuration binding, HS256 encoder configuration, `IssuedAccessToken`, and `JwtTokenService`.
- A deterministic JWT test using a fixed `Clock` and decoder to verify signature-related decoding, headers, issuer, subject, issued time, expiry, and TTL.
- `LoginRequest`, `LoginResponse`, `InvalidCredentialsException`, `AccountForbiddenException`, and `LoginService`.
- Login exception mappings, `LoginController`, Bean Validation, and seven MVC scenarios.
- Six `LoginService` unit scenarios: missing user, wrong password, deleted user, banned user, banned user with wrong password, and successful token issuance.
- A successful-login integration test covering real Spring wiring, MySQL, password matching, JWT signing/decoding, and the HTTP response.
- The Login checkpoint verification set passed 16/16: JWT properties 1, Controller 7, Service 6, JWT signing 1, full flow 1.
- Application V2 schema, entity, Mapper, create DTOs and Service, JWT decoder, Bearer user-ID resolver, Controller, and their focused persistence/unit/MVC tests are present. Resolver and Controller tests passed 8/8 after the 401 exception mapping was added.
- Application creation full-flow tests cover login-issued JWT, persisted owner ID, tampered-token rejection, same-owner duplicate names, and same-name creation by different owners. The complete test suite passed 46/46 with a test-only JWT secret.
- V3 introduces an active-only uniqueness slot so deleted names can be reused repeatedly. `listMyApplications` selects only the verified owner’s non-deleted rows and maps them to `ApplicationResponse`. Focused tests and the full suite passed 49/49 with a test-only JWT secret.

## Decisions and constraints

- Login uses email plus password. Normalize email, but do not strip the password.
- Missing user, wrong password, and deleted account share the same generic invalid-credentials failure to reduce account-state leakage.
- A banned account is revealed only after the password is correct.
- JWT `sub` contains the stable internal user ID. Tokens are issued only after all credential and account-state checks pass.
- Tests use a test-only JWT secret; production/local secrets remain external configuration.
- Keep the current learning-first workflow: business rules and test scenarios are developer-first; syntax and repetitive mechanics may be scaffolded or automated.

## First task in the new session

Add a GET endpoint for "my Applications" using only the Authorization header for identity; do not accept a client-supplied owner ID. Verify list response, empty list, invalid token, and cross-user isolation at the HTTP boundary. Pagination and update/delete behavior can be decided after this list slice.

## Relevant files

- `AGENTS.md`
- `nexus-server/src/main/java/com/nexus/auth/service/LoginService.java`
- `nexus-server/src/main/java/com/nexus/auth/token/JwtTokenService.java`
- `nexus-server/src/main/java/com/nexus/common/web/GlobalExceptionHandler.java`
- `nexus-server/src/main/java/com/nexus/auth/controller/LoginController.java`
- `nexus-server/src/test/java/com/nexus/auth/service/LoginServiceTests.java`
- `nexus-server/src/test/java/com/nexus/auth/token/JwtTokenServiceTests.java`
- `nexus-server/src/test/java/com/nexus/auth/controller/LoginControllerTests.java`
- `nexus-server/src/test/java/com/nexus/auth/flowTest/LoginFlowIntegrationTests.java`
- `nexus-server/src/main/java/com/nexus/application/controller/ApplicationController.java`
- `nexus-server/src/main/java/com/nexus/application/service/ApplicationServiceImpl.java`
- `nexus-server/src/main/java/com/nexus/auth/web/BearerUserIdResolver.java`
- `nexus-server/src/test/java/com/nexus/application/controller/ApplicationControllerTests.java`
- `nexus-server/src/test/java/com/nexus/auth/web/BearerUserIdResolverTests.java`
- `nexus-server/src/test/java/com/nexus/application/flowTest/ApplicationCreationFlowIntegrationTests.java`
- `nexus-server/src/main/resources/db/migration/V3__change_application.sql`
- `nexus-server/src/main/java/com/nexus/application/dto/ApplicationResponse.java`
