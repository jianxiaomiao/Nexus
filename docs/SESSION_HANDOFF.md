# Nexus Session Handoff

## Current stage

User Registration and Login have completed their current engineering checkpoints. Authenticated Application creation now has focused unit/MVC tests and a passing real HTTP → JWT → MySQL integration test.

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

## Decisions and constraints

- Login uses email plus password. Normalize email, but do not strip the password.
- Missing user, wrong password, and deleted account share the same generic invalid-credentials failure to reduce account-state leakage.
- A banned account is revealed only after the password is correct.
- JWT `sub` contains the stable internal user ID. Tokens are issued only after all credential and account-state checks pass.
- Tests use a test-only JWT secret; production/local secrets remain external configuration.
- Keep the current learning-first workflow: business rules and test scenarios are developer-first; syntax and repetitive mechanics may be scaffolded or automated.

## First task in the new session

Review the Application create API contract, error mappings, and soft-delete uniqueness rule. Confirm the authenticated creation request flow and failure paths are understood, then choose the next Application read/update/delete behavior with explicit ownership rules.

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
