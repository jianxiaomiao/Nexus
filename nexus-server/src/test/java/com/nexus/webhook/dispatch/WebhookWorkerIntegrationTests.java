package com.nexus.webhook.dispatch;

import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.crypto.*;
import com.nexus.webhook.entity.*;
import com.nexus.webhook.mapper.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class WebhookWorkerIntegrationTests {
    @Autowired WebhookOutboxMapper outbox;
    @Autowired ApplicationWebhookMapper configs;
    @Autowired WebhookSecretMapper secrets;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebhookSecretCrypto crypto;
    @Autowired ObjectMapper json;
    @Autowired WebhookWorker springWorker;
    @Autowired WebhookDispatcher dispatcher;
    @Autowired org.springframework.context.ApplicationContext context;
    User owner; Application app; ApplicationWebhook config;
    LeaseScheduler scheduler; WebhookLeaseManager leases; WebhookHttpClient http;
    WebhookWorkerImpl worker;
    final Clock clock = Clock.systemUTC();

    @BeforeEach void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        owner = new User(); owner.setEmail(UUID.randomUUID() + "@example.test");
        owner.setDisplayName("worker test"); owner.setPasswordHash("test-only-placeholder"); users.insert(owner);
        app = new Application(); app.setOwnerUserId(owner.getId()); app.setName("worker-" + UUID.randomUUID()); applications.insert(app);
        config = new ApplicationWebhook(); config.setApplicationId(app.getId()); config.setUrl("https://example.test/webhook"); config.setStatus(0); configs.insert(config);
        scheduler = new LeaseScheduler();
        leases = new WebhookLeaseManager(outbox, scheduler, Duration.ofMinutes(5), Duration.ofMinutes(1), new TransactionTemplate(transactions), System::nanoTime);
        http = mock(WebhookHttpClient.class);
        worker = create(new TransactionTemplate(transactions));
    }
    WebhookWorkerImpl create(TransactionTemplate tx) {
        var handler = new WebhookResponseHandler(new WebhookRetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(30)), clock, bound -> 1000);
        return new WebhookWorkerImpl(leases, new WebhookAttemptPreparer(outbox, tx, 1), outbox, configs, secrets, http, crypto,
                new WebhookSignatureService(), json, handler, new WebhookExceptionClassifier(), clock);
    }
    @AfterEach void cleanup() {
        if (worker != null) worker.close();
        if (leases != null) leases.close();
        if (scheduler != null) scheduler.close();
        if (app != null && app.getId() != null) {
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", app.getId());
            if (config != null && config.getId() != null) {
                jdbc.update("DELETE FROM webhook_secrets WHERE webhook_id = ?", config.getId());
                jdbc.update("DELETE FROM application_webhooks WHERE id = ?", config.getId());
            }
            applications.deleteById(app.getId());
        }
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }
    byte[] secret(long version) {
        byte[] raw = crypto.generateSecret(); var encrypted = crypto.encrypt(raw);
        var s = new WebhookSecret(); s.setWebhookId(config.getId()); s.setSecretVersion(version); s.setStatus(0);
        s.setSecretCiphertext(encrypted.ciphertext()); s.setSecretNonce(encrypted.nonce());
        s.setEncryptionAlgorithm(encrypted.encryptionAlgorithm()); s.setEncryptionKeyVersion(encrypted.encryptionKeyVersion());
        secrets.insert(s); return raw;
    }
    ClaimedWebhookEvent claim() {
        var row = new WebhookOutbox(); row.setEventId(UUID.randomUUID().toString()); row.setJobId(UUID.randomUUID().toString());
        row.setEventType("screenshot.completed"); row.setApplicationId(app.getId()); row.setEventStatus(0);
        row.setCompletedAt(LocalDateTime.of(2026, 1, 1, 0, 0)); row.setQueryUrl("https://example.test/job");
        row.setStatus(0); row.setAttemptCount(0L); row.setNextAttemptAt(LocalDateTime.of(1901, 1, 1, 0, 0)); outbox.insert(row);
        return claimExisting(row.getId());
    }
    ClaimedWebhookEvent claimExisting(long id) {
        String token = UUID.randomUUID().toString(); long start = System.nanoTime();
        assertEquals(1, outbox.claim(id, token, 300_000_000L));
        var snapshot = outbox.selectLeaseSnapshot(id, token);
        return new ClaimedWebhookEvent(outbox.selectById(id), token, snapshot.leaseExpiresAt(), start, LeaseTime.budgetNanos(snapshot));
    }
    WebhookHttpClient.PreparedPost post() throws Exception {
        var post = mock(WebhookHttpClient.PreparedPost.class);
        when(http.preparePost(anyString(), any(byte[].class), anyLong(), anyString())).thenReturn(post);
        return post;
    }
    WebhookOutbox row(ClaimedWebhookEvent c) { return outbox.selectById(c.event().getId()); }

    @Test void successPersistsAdmissionAndDelivered() throws Exception {
        secret(1); var c = claim(); var post = post();
        when(post.send()).thenAnswer(inv -> { assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());
            var committed = row(c); assertEquals(1L, committed.getAttemptCount());
            assertEquals(config.getUrl(), committed.getDeliveryUrl()); assertNotNull(committed.getWebhookSecretId());
            return new WebhookHttpResponse(200, null); });
        worker.process(c);
        assertEquals(2, row(c).getStatus()); assertEquals(1L, row(c).getAttemptCount());
        assertNotNull(row(c).getDeliveredAt()); verify(post).send();
    }
    @Test void applicationContextWiresWorkerAndDispatcher() {
        assertInstanceOf(WebhookWorkerImpl.class, springWorker); assertNotNull(dispatcher);
        assertEquals(1, context.getBeansOfType(WebhookWorker.class).size());
        assertEquals(1, context.getBeansOfType(WebhookDispatcher.class).size());
    }
    @Test void recoveredExhaustedBudgetNeverSendsAgain() {
        var c = claim();
        jdbc.update("UPDATE webhook_outbox SET attempt_count = 3, secret_refresh_attempted = 1 WHERE id = ?", c.event().getId());
        worker.process(c); assertEquals(3, row(c).getStatus()); assertEquals(3L, row(c).getAttemptCount());
        assertEquals("HTTP_RETRIES_EXHAUSTED", row(c).getLastFailureCode()); verifyNoInteractions(http);
    }
    @Test void retryPersistsBudgetAcrossClaims() throws Exception {
        secret(1); var c = claim(); var post = post(); when(post.send()).thenReturn(new WebhookHttpResponse(503, null));
        worker.process(c); assertEquals(0, row(c).getStatus()); assertEquals(1L, row(c).getAttemptCount());
        assertEquals("HTTP_503", row(c).getLastFailureCode());
        jdbc.update("UPDATE webhook_outbox SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", c.event().getId());
        var second = claimExisting(c.event().getId()); worker.process(second);
        assertEquals(3, row(c).getStatus()); assertEquals(2L, row(c).getAttemptCount());
        assertEquals("HTTP_RETRIES_EXHAUSTED", row(c).getLastFailureCode()); verify(post, times(2)).send();
    }
    @Test void ioFailureRetainsCommittedCount() throws Exception {
        secret(1); var c = claim(); var post = post(); when(post.send()).thenThrow(new IOException("test"));
        worker.process(c); assertEquals(0, row(c).getStatus()); assertEquals(1L, row(c).getAttemptCount());
        assertEquals("NETWORK_ERROR", row(c).getLastFailureCode());
    }
    @Test void unauthorizedRefreshesOnceUsingNewCurrentSecret() throws Exception {
        byte[] first = secret(1); var c = claim(); var post = post(); java.util.List<byte[]> payloads = new java.util.ArrayList<>();
        doAnswer(i -> { payloads.add(i.getArgument(1)); return post; }).when(http).preparePost(anyString(), any(byte[].class), anyLong(), anyString());
        byte[][] replacement = new byte[1][];
        when(post.send()).thenAnswer(i -> {
            jdbc.update("UPDATE webhook_secrets SET status = 1 WHERE webhook_id = ?", config.getId());
            replacement[0] = secret(2); return new WebhookHttpResponse(401, null);
        }).thenReturn(new WebhookHttpResponse(200, null));
        worker.process(c); assertEquals(2, row(c).getStatus()); assertEquals(2L, row(c).getAttemptCount());
        assertTrue(row(c).getSecretRefreshAttempted());
        assertEquals(secrets.selectCurrentSecretsByWebhookId(config.getId(), LocalDateTime.now(ZoneOffset.UTC)).get(0).getId(), row(c).getWebhookSecretId());
        var times = org.mockito.ArgumentCaptor.forClass(Long.class); var signatures = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(http, times(2)).preparePost(anyString(), any(byte[].class), times.capture(), signatures.capture());
        assertArrayEquals(payloads.get(0), payloads.get(1));
        assertEquals(c.event().getEventId(), json.readTree(payloads.get(0)).get("eventId").asText());
        assertEquals(c.event().getEventId(), json.readTree(payloads.get(1)).get("eventId").asText());
        var signer = new WebhookSignatureService();
        assertEquals(signer.sign(first, times.getAllValues().get(0), payloads.get(0)), signatures.getAllValues().get(0));
        assertEquals(signer.sign(replacement[0], times.getAllValues().get(1), payloads.get(1)), signatures.getAllValues().get(1));
    }
    @Test void disabledConfigurationPausesWithoutAdmission() throws Exception {
        secret(1); var c = claim(); jdbc.update("UPDATE application_webhooks SET status = 1 WHERE id = ?", config.getId());
        var before = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        worker.process(c); assertEquals(0, row(c).getStatus()); assertEquals(0L, row(c).getAttemptCount());
        assertEquals("CONFIG_DISABLED", row(c).getLastFailureCode()); verifyNoInteractions(http);
        assertTrue(row(c).getNextAttemptAt().isAfter(before));
        assertFalse(outbox.selectDueForUpdate(100).stream().anyMatch(e -> e.getId().equals(c.event().getId())));
        jdbc.update("UPDATE application_webhooks SET status = 0 WHERE id = ?", config.getId());
        jdbc.update("UPDATE webhook_outbox SET next_attempt_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", c.event().getId());
        var post = post(); when(post.send()).thenReturn(new WebhookHttpResponse(200, null));
        worker.process(claimExisting(c.event().getId()));
        assertEquals(2, row(c).getStatus()); assertEquals(1L, row(c).getAttemptCount()); verify(post).send();
    }
    @Test void dnsFailureRetriesWithoutAdmission() throws Exception {
        secret(1); var c = claim();
        when(http.preparePost(anyString(), any(byte[].class), anyLong(), anyString())).thenThrow(new java.net.UnknownHostException());
        var before = jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
        worker.process(c); var current = row(c);
        assertEquals(0, current.getStatus()); assertEquals(0L, current.getAttemptCount());
        assertEquals("DNS_RESOLUTION_FAILED", current.getLastFailureCode());
        assertTrue(current.getNextAttemptAt().isAfter(before));
    }
    @Test void disallowedAddressIsPermanentWithoutAdmission() throws Exception {
        secret(1); var c = claim();
        when(http.preparePost(anyString(), any(byte[].class), anyLong(), anyString())).thenThrow(new WebhookDisallowedAddressException());
        worker.process(c); var current = row(c);
        assertEquals(3, current.getStatus()); assertEquals(0L, current.getAttemptCount());
        assertEquals("ADDRESS_DISALLOWED", current.getLastFailureCode());
    }
    @Test void persistedRefreshFlagPreventsSecondRefreshAfterReclaim() throws Exception {
        secret(1); var c = claim(); var post = post();
        jdbc.update("UPDATE webhook_outbox SET attempt_count = 2, secret_refresh_attempted = 1 WHERE id = ?", c.event().getId());
        when(post.send()).thenReturn(new WebhookHttpResponse(401, null)); worker.process(c);
        assertEquals(3, row(c).getStatus()); assertEquals(3L, row(c).getAttemptCount());
        assertTrue(row(c).getSecretRefreshAttempted()); assertEquals("HTTP_401_REFRESH_EXHAUSTED", row(c).getLastFailureCode());
        verify(post).send(); verify(http).preparePost(anyString(), any(byte[].class), anyLong(), anyString());
    }
    @Test void retryAfterPersistsServerDelay() throws Exception {
        secret(1); var c = claim(); var post = post(); when(post.send()).thenReturn(new WebhookHttpResponse(429, "23"));
        worker.process(c); assertEquals(0, row(c).getStatus()); assertEquals(1L, row(c).getAttemptCount());
        assertEquals("HTTP_429", row(c).getLastFailureCode());
        long seconds = jdbc.queryForObject("SELECT TIMESTAMPDIFF(SECOND, UTC_TIMESTAMP(6), next_attempt_at) FROM webhook_outbox WHERE id = ?", Long.class, c.event().getId());
        assertTrue(seconds >= 20 && seconds <= 23, "remaining delay: " + seconds);
    }
    @Test void changedTokenCannotCompleteDelivery() throws Exception {
        secret(1); var c = claim(); var post = post();
        when(post.send()).thenAnswer(i -> { jdbc.update("UPDATE webhook_outbox SET claim_token = ? WHERE id = ?", UUID.randomUUID().toString(), c.event().getId()); return new WebhookHttpResponse(200, null); });
        worker.process(c); assertEquals(1, row(c).getStatus()); assertNull(row(c).getDeliveredAt());
    }
    @Test void uncertainAdmissionCommitNeverSends() throws Exception {
        secret(1); var c = claim(); var post = post(); var commits = new AtomicInteger();
        PlatformTransactionManager uncertain = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition d) { return transactions.getTransaction(d); }
            public void commit(TransactionStatus s) { transactions.commit(s); if (commits.incrementAndGet() == 1) throw new IllegalStateException("lost acknowledgement"); }
            public void rollback(TransactionStatus s) { transactions.rollback(s); }
        };
        worker.close(); worker = create(new TransactionTemplate(uncertain)); worker.process(c);
        assertEquals(1L, row(c).getAttemptCount()); assertEquals(1, row(c).getStatus()); verify(post, never()).send();
    }
    @Test void invalidMetadataRollsBackAdmissionBeforeCallback() {
        var c = claim(); var sends = new AtomicInteger();
        var preparer = new WebhookAttemptPreparer(outbox, new TransactionTemplate(transactions), 1);
        assertThrows(RuntimeException.class, () -> preparer.prepareAndSend(c.event().getId(), c.claimToken(),
                new WebhookAttemptPreparer.Snapshot(0, false), WebhookAttemptPreparer.AttemptType.INITIAL,
                "https://example.test/webhook", Long.MAX_VALUE, committed -> { sends.incrementAndGet(); return "sent"; }));
        var current = row(c); assertEquals(0L, current.getAttemptCount()); assertFalse(current.getSecretRefreshAttempted());
        assertNull(current.getDeliveryUrl()); assertNull(current.getWebhookSecretId()); assertEquals(0, sends.get());
    }
    @Test void closeWhileSendInProgressPreventsFinalWrite() throws Exception {
        secret(1); var c = claim(); var post = post(); var started = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(post.send()).thenAnswer(i -> { started.countDown(); assertTrue(release.await(10, TimeUnit.SECONDS)); return new WebhookHttpResponse(200, null); });
        var executor = Executors.newSingleThreadExecutor();
        try { var result = executor.submit(() -> worker.process(c)); assertTrue(started.await(10, TimeUnit.SECONDS));
            worker.close(); release.countDown(); result.get(10, TimeUnit.SECONDS);
            assertEquals(1, row(c).getStatus()); assertNull(row(c).getDeliveredAt());
        } finally { release.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
    }
}
