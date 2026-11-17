package com.nexus.auth.flowTest;

import com.nexus.auth.dto.*;
import com.nexus.auth.entity.*;
import com.nexus.auth.exception.*;
import com.nexus.auth.mapper.*;
import com.nexus.auth.service.*;
import com.nexus.auth.token.*;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.user.service.UserStatusService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.dao.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtEncodingException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import javax.sql.DataSource;
import java.sql.Connection;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 真实Spring/MySQL/事务/HTTP；不创建截图任务、不访问外站。 */
@SpringBootTest(properties = {"nexus.auth.session.cleanup-enabled=false", "nexus.auth.jwt.issuer=nexus-auth-test",
        "nexus.auth.jwt.access-token-ttl=30m", "nexus.auth.session.absolute-ttl=24h"})
@AutoConfigureMockMvc
class AuthSessionIntegrationTests {
    static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");
    static final String PASSWORD = "test-only-password";
    @Autowired LoginService login;
    @Autowired AuthSessionService service;
    @Autowired LogoutService logout;
    @Autowired LogoutDatabase logoutDatabase;
    @Autowired AuthSessionCleanup cleanup;
    @Autowired BearerUserIdResolver bearer;
    @Autowired UserStatusService accountStatus;
    @Autowired UserMapper users;
    @MockitoSpyBean AuthSessionMapper sessions;
    @Autowired RefreshTokenMapper refreshTokens;
    @MockitoSpyBean RefreshTokenGenerator generator;
    @Autowired PasswordEncoder passwords;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired DataSource dataSource;
    @Autowired MutableClock clock;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired com.nexus.application.mapper.ApplicationMapper applications;
    @Autowired com.nexus.apikey.mapper.ApiKeyMapper apiKeys;
    @Autowired com.nexus.apikey.credential.ApiKeyCredentialGenerator apiKeyGenerator;
    @Autowired com.nexus.auth.ApiKeyAuthenticator.ApiKeyAuthenticator machineAuthentication;
    @MockitoSpyBean JwtTokenService jwt;
    final List<Long> userIds = new ArrayList<>();

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfiguration {
        @Bean @Primary MutableClock testClock() { return new MutableClock(); }
    }
    static class MutableClock extends Clock {
        final AtomicReference<Instant> value = new AtomicReference<>(NOW);
        void set(Instant now) { value.set(now); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return Clock.fixed(instant(), zone); }
        public Instant instant() { return value.get(); }
    }
    @BeforeEach void before() { clock.set(NOW); }
    @AfterEach void after() {
        reset(jwt, sessions, generator); clock.set(NOW);
        for (long id : userIds) {
            jdbc.update("DELETE rt FROM refresh_tokens rt JOIN auth_sessions s ON s.id=rt.session_id WHERE s.user_id=?", id);
            jdbc.update("DELETE FROM auth_sessions WHERE user_id=?", id);
            jdbc.update("DELETE FROM users WHERE id=?", id);
        }
    }

    @Test void migrationRunsOnTheDedicatedSchemaAndMatchesApprovedConstraints() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals("+00:00", jdbc.queryForObject("SELECT @@session.time_zone", String.class));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE version='14' AND success=1", Integer.class));
        User user = user(); AuthTokensResponse response = login(user);
        long sid = id(response); String hash = generator.hash(response.refreshToken());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("INSERT INTO refresh_tokens(session_id,token_hash,created_at) VALUES(?,?,?)", sid, hash, utc(NOW)));
        assertCheckViolation(() -> jdbc.update("UPDATE auth_sessions SET expires_at=created_at WHERE id=?", sid));
        assertCheckViolation(() -> jdbc.update("UPDATE auth_sessions SET revoked_at=created_at-INTERVAL 1 SECOND WHERE id=?", sid));
        assertCheckViolation(() -> jdbc.update("UPDATE refresh_tokens SET consumed_at=created_at-INTERVAL 1 SECOND WHERE session_id=?", sid));
        assertCheckViolation(() -> jdbc.update("UPDATE refresh_tokens SET token_hash='short' WHERE session_id=?", sid));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM auth_sessions WHERE id=?", sid));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("DELETE FROM users WHERE id=?", user.getId()));
    }

    @Test void loginCreatesAnIndependentSessionAndPersistsOnlyTheRefreshHash() {
        User user = user(); AuthTokensResponse first = login(user); AuthTokensResponse second = login(user);
        assertNotEquals(first.sessionId(), second.sessionId());
        assertEquals(NOW.plus(Duration.ofHours(24)), first.sessionExpiresAt());
        assertEquals(1800, first.expiresInSeconds());
        assertEquals(43, first.refreshToken().length());
        assertEquals(generator.hash(first.refreshToken()), storedRefresh(first).getTokenHash());
        assertEquals(user.getId().longValue(), bearer.resolve(header(first)));
        assertEquals(user.getId().longValue(), bearer.resolve(header(second)));
    }

    @Test void refreshConsumesOneGenerationAndKeepsSessionAndAbsoluteExpiry() {
        User user = user(); AuthTokensResponse first = login(user);
        clock.set(NOW.plusSeconds(1900));
        AuthTokensResponse next = service.refresh(first.refreshToken());
        assertEquals(first.sessionId(), next.sessionId());
        assertEquals(first.sessionExpiresAt(), next.sessionExpiresAt());
        assertNotEquals(first.refreshToken(), next.refreshToken());
        assertNotNull(storedRefresh(first).getConsumedAt());
        assertNull(storedRefresh(next).getConsumedAt());
        assertEquals(2, tokenCount(id(first)));
        assertEquals(user.getId().longValue(), bearer.resolve(header(next)));
    }

    @Test void ordinaryRotationLeavesUnexpiredAccessTokensUsable() {
        User user = user(); AuthTokensResponse first = login(user);
        clock.set(NOW.plusSeconds(60)); service.refresh(first.refreshToken());
        assertEquals(user.getId().longValue(), bearer.resolve(header(first)));
    }

    @Test void confirmedReplayCommitsRevocationBeforeReportingFailureAndLeavesOtherSessionsAlone() {
        User user = user(); AuthTokensResponse first = login(user); AuthTokensResponse other = login(user);
        AuthTokensResponse next = service.refresh(first.refreshToken());
        assertThrows(RefreshTokenReplayException.class, () -> service.refresh(first.refreshToken()));
        assertNotNull(sessions.selectById(id(first)).getRevokedAt());
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(next)));
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(next.refreshToken()));
        assertEquals(user.getId().longValue(), bearer.resolve(header(other)));
    }

    @Test void unknownAndMalformedRefreshTokensDoNotRevokeKnownSessions() {
        User user = user(); AuthTokensResponse first = login(user);
        for (String input : Arrays.asList(null, "", "malformed", generator.generate().value())) {
            assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(input));
        }
        assertNull(sessions.selectById(id(first)).getRevokedAt());
        assertEquals(1, tokenCount(id(first)));
    }

    @Test void signingFailureRollsBackTheConsumptionAndNewGeneration() {
        User user = user(); AuthTokensResponse first = login(user);
        doThrow(new JwtEncodingException("controlled test signing failure")).when(jwt)
                .issueAccessToken(eq(user.getId()), eq(id(first)), any(Instant.class), any(Instant.class));
        assertThrows(AuthSessionUnavailableException.class, () -> service.refresh(first.refreshToken()));
        assertNull(storedRefresh(first).getConsumedAt());
        assertNull(sessions.selectById(id(first)).getRevokedAt());
        assertEquals(1, tokenCount(id(first)));
    }

    @Test void loginSigningFailureDoesNotLeaveAnOrphanSessionOrRefreshToken() {
        User user = user();
        doThrow(new JwtEncodingException("controlled test signing failure")).when(jwt)
                .issueAccessToken(eq(user.getId()), anyLong(), any(Instant.class), any(Instant.class));
        assertThrows(AuthSessionUnavailableException.class, () -> login(user));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?", Integer.class, user.getId()));
    }

    @Test void nextRefreshInsertFailureRollsBackTheOldConsumption() {
        User user = user(); AuthTokensResponse first = login(user);
        GeneratedRefreshToken collision = new GeneratedRefreshToken(first.refreshToken(), generator.hash(first.refreshToken()));
        doReturn(collision).when(generator).generate();
        assertThrows(AuthSessionUnavailableException.class, () -> service.refresh(first.refreshToken()));
        assertNull(storedRefresh(first).getConsumedAt());
        assertEquals(1, tokenCount(id(first)));
    }

    @Test void loginNormalizesEmailAndRejectsAChangedPasswordSnapshotBeforeCreatingASession() {
        User user = user();
        assertDoesNotThrow(() -> login.login(new LoginRequest(" " + user.getEmail().toUpperCase(Locale.ROOT) + " ", PASSWORD)));
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?", Integer.class, user.getId());
        assertThrows(InvalidCredentialsException.class, () -> service.create(user.getId(), "obsolete-test-password-hash"));
        assertEquals(before, jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id=?", Integer.class, user.getId()));
    }

    @Test void logoutAcceptsExpiredAccessProofIsIdempotentAndDoesNotAffectAnotherSession() {
        User user = user(); AuthTokensResponse first = login(user); AuthTokensResponse other = login(user);
        clock.set(NOW.plusSeconds(1801));
        logout.logout(header(first));
        LocalDateTime revoked = sessions.selectById(id(first)).getRevokedAt();
        clock.set(NOW.plusSeconds(1900)); logout.logout(header(first));
        assertEquals(revoked, sessions.selectById(id(first)).getRevokedAt());
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(first.refreshToken()));
        assertNull(sessions.selectById(id(other)).getRevokedAt());
        assertDoesNotThrow(() -> service.refresh(other.refreshToken()));
    }

    @Test void logoutImmediatelyRejectsOldAccessTokensAndUnknownProofCannotRevoke() {
        User user = user(); AuthTokensResponse first = login(user);
        assertThrows(InvalidAccessTokenException.class, () -> logout.logout("Bearer not-a-jwt"));
        assertNull(sessions.selectById(id(first)).getRevokedAt());
        logout.logout(header(first));
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
    }

    @Test void correctlySignedButMismatchedUserAndSessionCannotAuthorizeOrRevoke() {
        User owner = user(); User stranger = user(); AuthTokensResponse first = login(owner);
        String header = "Bearer " + jwt.issueAccessToken(stranger.getId(), id(first), first.sessionExpiresAt()).value();
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header));
        assertThrows(InvalidAccessTokenException.class, () -> logout.logout(header));
        assertNull(sessions.selectById(id(first)).getRevokedAt());
    }

    @Test void disableRevokesAllSessionsAndRestoreNeverReactivatesThem() {
        User user = user(); AuthTokensResponse first = login(user); AuthTokensResponse other = login(user);
        service.refresh(first.refreshToken()); // 同时存在已消费RT、已撤销会话和禁用账号时，账号状态优先。
        accountStatus.disable(user.getId());
        assertThrows(AccountForbiddenException.class, () -> service.refresh(first.refreshToken()));
        assertThrows(AccountForbiddenException.class, () -> bearer.resolve(header(first)));
        assertThrows(AccountForbiddenException.class, () -> login(user));
        assertNotNull(sessions.selectById(id(first)).getRevokedAt());
        assertNotNull(sessions.selectById(id(other)).getRevokedAt());
        accountStatus.restore(user.getId());
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(first.refreshToken()));
        assertDoesNotThrow(() -> bearer.resolve(header(login(user))));
    }

    @Test void deletedUserHasInvalidCredentialPriority() {
        User user = user(); AuthTokensResponse first = login(user);
        jdbc.update("UPDATE users SET status=1,is_deleted=1,deleted_at=? WHERE id=?", utc(NOW), user.getId());
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(first.refreshToken()));
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
        assertThrows(InvalidCredentialsException.class, () -> login(user));
    }

    @Test void humanLogoutDoesNotAffectMachineCredentialsAndAccountRestoreKeepsTheirOwnLifecycle() {
        User user = user();
        var app = new com.nexus.application.entity.Application(); app.setOwnerUserId(user.getId());
        app.setName("auth-machine-isolation-" + UUID.randomUUID()); applications.insert(app);
        var generated = apiKeyGenerator.generate();
        var key = new com.nexus.apikey.entity.ApiKey(); key.setApplicationId(app.getId()); key.setName("test machine key");
        key.setPublicId(generated.publicId()); key.setSecretHash(generated.secretHash()); key.setKeyPreview(generated.keyPreview());
        apiKeys.insert(key);
        try {
            AuthTokensResponse first = login(user);
            var machine = machineAuthentication.authenticate(generated.plaintextKey());
            logout.logout(header(first));
            assertEquals(machine, machineAuthentication.authenticate(generated.plaintextKey()));
            accountStatus.disable(user.getId());
            assertThrows(ApiKeyForbiddenException.class, () -> machineAuthentication.authenticate(generated.plaintextKey()));
            accountStatus.restore(user.getId());
            assertEquals(machine, machineAuthentication.authenticate(generated.plaintextKey()));
            assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
        } finally {
            apiKeys.deleteById(key.getId()); applications.deleteById(app.getId());
        }
    }

    @Test void lastAccessTokenIsClippedAndExactSessionDeadlineRejectsRefresh() {
        User user = user(); AuthTokensResponse first = login(user);
        clock.set(first.sessionExpiresAt().minusSeconds(600));
        AuthTokensResponse next = service.refresh(first.refreshToken());
        assertEquals(600, next.expiresInSeconds());
        clock.set(first.sessionExpiresAt());
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(next)));
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(next.refreshToken()));
    }

    @Test void insufficientRemainingWholeSecondDoesNotConsumeRefreshToken() {
        User user = user(); AuthTokensResponse first = login(user);
        clock.set(first.sessionExpiresAt().minusMillis(500));
        assertThrows(InvalidRefreshTokenException.class, () -> service.refresh(first.refreshToken()));
        assertNull(storedRefresh(first).getConsumedAt());
    }

    @Test void twoConcurrentRefreshesProduceOnlyOneSuccessThenStrictReplayRevokesTheChain() throws Exception {
        User user = user(); AuthTokensResponse first = login(user);
        List<Object> results = race(() -> service.refresh(first.refreshToken()), () -> service.refresh(first.refreshToken()));
        assertEquals(1, results.stream().filter(AuthTokensResponse.class::isInstance).count());
        assertEquals(1, results.stream().filter(RefreshTokenReplayException.class::isInstance).count());
        assertEquals(2, tokenCount(id(first)));
        assertNotNull(sessions.selectById(id(first)).getRevokedAt());
        AuthTokensResponse winner = (AuthTokensResponse) results.stream().filter(AuthTokensResponse.class::isInstance).findFirst().orElseThrow();
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(winner)));
    }

    @Test void concurrentRefreshAndLogoutCannotLeaveTheSessionActive() throws Exception {
        User user = user(); AuthTokensResponse first = login(user);
        List<Object> results = race(() -> service.refresh(first.refreshToken()), () -> { logout.logout(header(first)); return "logged-out"; });
        assertTrue(results.contains("logged-out"));
        assertTrue(results.stream().allMatch(value -> value instanceof AuthTokensResponse || value instanceof InvalidRefreshTokenException || value.equals("logged-out")));
        assertNotNull(sessions.selectById(id(first)).getRevokedAt());
        assertThrows(InvalidAccessTokenException.class, () -> bearer.resolve(header(first)));
    }

    @Test void concurrentLoginAndDisableNeverLeaveAnUnrevokedSessionForTheDisabledUser() throws Exception {
        User user = user();
        List<Object> results = race(() -> login(user), () -> { accountStatus.disable(user.getId()); return "disabled"; });
        assertTrue(results.contains("disabled"));
        assertTrue(results.stream().allMatch(value -> value instanceof AuthTokensResponse || value instanceof AccountForbiddenException || value.equals("disabled")));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL", Integer.class, user.getId()));
    }

    @Test void outerTransactionIsRejectedInsteadOfReturningUncommittedCredentials() {
        User user = user(); AuthTokensResponse first = login(user);
        assertThrows(IllegalStateException.class, () -> new TransactionTemplate(transactionManager).execute(status -> service.refresh(first.refreshToken())));
        assertNull(storedRefresh(first).getConsumedAt());
    }

    @Test void logoutOfACleanedSessionIsIdempotentAndLeavesANewLoginAlone() {
        User user = user(); AuthTokensResponse first = login(user);
        clock.set(first.sessionExpiresAt().plus(Duration.ofHours(72)));
        assertEquals(1, cleanup.cleanupBatch());
        AuthTokensResponse next = login(user);
        assertDoesNotThrow(() -> logout.logout(header(first)));
        assertNull(sessions.selectById(id(first)));
        assertEquals(user.getId().longValue(), bearer.resolve(header(next)));
    }

    @Test void cleanupKeepsActiveHistoryAndDeletesOnlySessionsEndedAtLeast72HoursAgo() {
        User user = user();
        long expired = agedSession(user, NOW.minus(Duration.ofHours(72)), null);
        long recent = agedSession(user, NOW.minus(Duration.ofHours(72)).plusMillis(1), null);
        long revoked = agedSession(user, NOW.plusSeconds(3600), NOW.minus(Duration.ofHours(72)));
        long active = agedSession(user, NOW.plusSeconds(3600), null);
        assertEquals(2, cleanup.cleanupBatch());
        assertNull(sessions.selectById(expired)); assertEquals(0, tokenCount(expired));
        assertNull(sessions.selectById(revoked)); assertEquals(0, tokenCount(revoked));
        assertNotNull(sessions.selectById(recent)); assertEquals(1, tokenCount(recent));
        assertNotNull(sessions.selectById(active)); assertEquals(1, tokenCount(active));
    }

    @Test void lostLogoutAcknowledgementCanSafelyRepeatTheOriginalCommittedRevocation() {
        User user = user(); AuthTokensResponse first = login(user); AuthTokensResponse other = login(user);
        var identity = new AccessTokenIdentity(user.getId(), id(first));
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        new LogoutRetry().execute(() -> {
            logoutDatabase.revoke(identity);
            if (attempts.incrementAndGet() == 1) throw new DataAccessResourceFailureException("controlled lost acknowledgement", new java.sql.SQLException("test", "08S01"));
        });
        assertEquals(2, attempts.get());
        assertNotNull(sessions.selectById(id(first)).getRevokedAt());
        assertNull(sessions.selectById(id(other)).getRevokedAt());
    }

    @Test void cleanupFailureAfterChildDeletionRollsBackTheWholeSessionDeletion() {
        User user = user(); long sid = agedSession(user, NOW.minus(Duration.ofHours(72)), null);
        doThrow(new DataAccessResourceFailureException("controlled delete failure")).when(sessions).deleteById(sid);
        assertEquals(0, cleanup.cleanupBatch());
        assertNotNull(sessions.selectById(sid));
        assertEquals(1, tokenCount(sid));
    }

    @Test void failureToRevokeSessionsRollsBackTheAccountDisableAsWell() {
        User user = user(); AuthTokensResponse first = login(user);
        doThrow(new DataAccessResourceFailureException("controlled revocation failure")).when(sessions).revokeAll(eq(user.getId()), any());
        assertThrows(DataAccessException.class, () -> accountStatus.disable(user.getId()));
        assertEquals(0, users.selectById(user.getId()).getStatus());
        assertNull(sessions.selectById(id(first)).getRevokedAt());
        assertEquals(user.getId().longValue(), bearer.resolve(header(first)));
    }

    @Test void lockedUserExhaustsLogoutWithinTheExistingForwardingWindowWithoutClaimingSuccess() throws Exception {
        User user = user(); AuthTokensResponse first = login(user);
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM users WHERE id=? FOR UPDATE")) {
                statement.setLong(1, user.getId()); statement.executeQuery().close();
            }
            long started = System.nanoTime();
            assertThrows(AuthSessionUnavailableException.class, () -> logout.logout(header(first)));
            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(8)) < 0);
            connection.rollback();
        }
        assertNull(sessions.selectById(id(first)).getRevokedAt());
    }

    @Test void realHttpLoginRefreshLogoutUseTheAgreedEnvelopeAndNoStoreHeaders() throws Exception {
        User user = user();
        String body = mvc.perform(post("/api/auth/login").contentType("application/json")
                        .content(json.writeValueAsString(new LoginRequest(user.getEmail(), PASSWORD))))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.refreshToken").isString()).andExpect(jsonPath("$.data.sessionId").isString())
                .andReturn().getResponse().getContentAsString();
        var data = json.readTree(body).path("data");
        String refresh = data.path("refreshToken").asText();
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(json.writeValueAsString(new RefreshRequest(refresh))))
                .andExpect(status().isOk()).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.data.sessionId").value(data.path("sessionId").asText()));
        mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + data.path("accessToken").asText()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").isEmpty());
        mvc.perform(post("/api/auth/refresh").contentType("application/json").content(json.writeValueAsString(new RefreshRequest(refresh))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_REFRESH_TOKEN"));
    }

    User user() {
        User user = new User(); user.setEmail("auth-session-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("auth test"); user.setPasswordHash(passwords.encode(PASSWORD));
        users.insert(user); userIds.add(user.getId()); return user;
    }
    void assertCheckViolation(Runnable action) {
        DataAccessException failure = assertThrows(DataAccessException.class, action::run);
        assertInstanceOf(java.sql.SQLException.class, failure.getMostSpecificCause());
        assertEquals(3819, ((java.sql.SQLException) failure.getMostSpecificCause()).getErrorCode());
    }
    AuthTokensResponse login(User user) { return login.login(new LoginRequest(user.getEmail(), PASSWORD)); }
    long id(AuthTokensResponse response) { return Long.parseLong(response.sessionId()); }
    String header(AuthTokensResponse response) { return "Bearer " + response.accessToken(); }
    LocalDateTime utc(Instant value) { return LocalDateTime.ofInstant(value, ZoneOffset.UTC); }
    RefreshToken storedRefresh(AuthTokensResponse response) { return refreshTokens.selectByHash(generator.hash(response.refreshToken())); }
    int tokenCount(long sessionId) { return jdbc.queryForObject("SELECT COUNT(*) FROM refresh_tokens WHERE session_id=?", Integer.class, sessionId); }
    long agedSession(User user, Instant expiry, Instant revoked) {
        AuthSession session = new AuthSession(); session.setUserId(user.getId()); session.setCreatedAt(utc(NOW.minus(Duration.ofDays(5))));
        session.setExpiresAt(utc(expiry)); session.setRevokedAt(revoked == null ? null : utc(revoked)); sessions.insert(session);
        RefreshToken rt = new RefreshToken(); rt.setSessionId(session.getId()); rt.setTokenHash(generator.generate().tokenHash());
        rt.setCreatedAt(session.getCreatedAt()); rt.setConsumedAt(session.getCreatedAt()); refreshTokens.insert(rt); return session.getId();
    }
    List<Object> race(Callable<Object> first, Callable<Object> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> action : List.of(first, second)) futures.add(executor.submit(() -> {
                start.await(); try { return action.call(); } catch (RuntimeException exception) { return exception; }
            }));
            start.countDown();
            return List.of(futures.get(0).get(20, TimeUnit.SECONDS), futures.get(1).get(20, TimeUnit.SECONDS));
        }
    }
}
