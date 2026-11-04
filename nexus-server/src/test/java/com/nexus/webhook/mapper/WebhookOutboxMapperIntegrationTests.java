package com.nexus.webhook.mapper;

import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.dispatch.WebhookLeaseManager;
import com.nexus.webhook.dispatch.LeaseScheduler;
import com.nexus.webhook.dispatch.ClaimedWebhookEvent;
import com.nexus.webhook.dispatch.LeaseStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class WebhookOutboxMapperIntegrationTests {
    private static final LocalDateTime NOW = LocalDateTime.of(1901, 1, 1, 0, 0);
    @Autowired WebhookOutboxMapper outbox;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    @Autowired WebhookLeaseManager leaseManager;
    @Autowired LeaseScheduler leaseScheduler;
    private User owner;
    private Application application;

    @BeforeEach
    void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        owner = new User();
        owner.setEmail(UUID.randomUUID() + "@example.test");
        owner.setDisplayName("outbox test");
        owner.setPasswordHash("test-only-placeholder");
        users.insert(owner);
        application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("outbox-" + UUID.randomUUID());
        applications.insert(application);
    }

    @AfterEach
    void cleanup() {
        if (application != null && application.getId() != null) {
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", application.getId());
            applications.deleteById(application.getId());
        }
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }

    private WebhookOutbox insert(LocalDateTime due) {
        WebhookOutbox row = new WebhookOutbox();
        row.setEventId(UUID.randomUUID().toString());
        row.setJobId(UUID.randomUUID().toString());
        row.setEventType("screenshot.completed");
        row.setApplicationId(application.getId());
        row.setEventStatus(0);
        row.setCompletedAt(NOW);
        row.setQueryUrl("https://example.test/job");
        row.setStatus(0);
        row.setAttemptCount(0L);
        row.setNextAttemptAt(due);
        outbox.insert(row);
        return row;
    }

    @Test
    void committedWriteWithClientSideCommitExceptionRemainsUncertainWithoutReclaim() {
        var row = insert(NOW);
        assertEquals(1, outbox.claim(row.getId(), "committed-token", 300_000_000L));
        row = outbox.selectById(row.getId());
        final var claimed = row;
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var writes = new AtomicInteger();
        var cancellations = new AtomicInteger();
        class CommitAcknowledgementException extends RuntimeException { }
        PlatformTransactionManager ambiguousCommit = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return transactions.getTransaction(definition);
            }
            @Override public void commit(TransactionStatus status) {
                transactions.commit(status);
                commits.incrementAndGet();
                throw new CommitAcknowledgementException();
            }
            @Override public void rollback(TransactionStatus status) {
                rollbacks.incrementAndGet();
                transactions.rollback(status);
            }
        };
        try (var manager = new WebhookLeaseManager(outbox, leaseScheduler, Clock.systemUTC(),
                Duration.ofMinutes(5), Duration.ofMinutes(1), new TransactionTemplate(ambiguousCommit))) {
            var session = manager.start(new ClaimedWebhookEvent(claimed, "committed-token", claimed.getLeaseExpiresAt(), System.nanoTime(), Duration.ofMinutes(5).toNanos()));
            session.cancellation().onCancel(cancellations::incrementAndGet);
            assertThrows(CommitAcknowledgementException.class, () -> session.complete(() -> {
                writes.incrementAndGet();
                return outbox.markDelivered(claimed.getId(), "committed-token");
            }));
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, session.status());
            assertTrue(session.cancellation().isCancelled());
            assertEquals(1, commits.get());
            assertEquals(0, rollbacks.get());
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, session.complete(() -> { writes.incrementAndGet(); return 1; }));
            session.close();
        }
        var delivered = outbox.selectById(claimed.getId());
        assertEquals(2, delivered.getStatus());
        assertNull(delivered.getClaimToken());
        assertNull(delivered.getLeaseExpiresAt());
        assertEquals(0, outbox.recoverExpired(claimed.getId(), "committed-token"));
        assertEquals(0, outbox.releaseClaim(claimed.getId(), "committed-token"));
        assertEquals(0, outbox.claim(claimed.getId(), "new-token", 300_000_000L));
        assertEquals(2, outbox.selectById(claimed.getId()).getStatus());
        assertEquals(1, writes.get());
        assertEquals(1, cancellations.get());
    }

    @Test
    void claimAndRenewUseDatabaseTimeAndReturnSnapshot() {
        var row = insert(NOW);
        LocalDateTime beforeClaim = dbNow();
        assertEquals(1, outbox.claim(row.getId(), "current", 60_000_000L));
        LocalDateTime afterClaim = dbNow();
        var snapshot = outbox.selectLeaseSnapshot(row.getId(), "current");
        assertNotNull(snapshot);
        assertNull(outbox.selectLeaseSnapshot(row.getId(), "old"));
        assertBetween(snapshot.leaseExpiresAt(), beforeClaim.plusSeconds(60), afterClaim.plusSeconds(60));
        assertBetween(snapshot.databaseNow(), beforeClaim, dbNow());
        assertEquals(0, outbox.recoverExpired(row.getId(), "current"));
        LocalDateTime beforeRenew = dbNow();
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            assertNotNull(outbox.lockForCompletion(row.getId()));
            assertEquals(0, outbox.renewLease(row.getId(), "old", 120_000_000L));
            assertEquals(1, outbox.renewLease(row.getId(), "current", 120_000_000L));
        });
        var renewed = outbox.selectLeaseSnapshot(row.getId(), "current");
        assertBetween(renewed.leaseExpiresAt(), beforeRenew.plusSeconds(120), dbNow().plusSeconds(120));
        assertBetween(renewed.databaseNow(), beforeRenew, dbNow());
        expire(row.getId());
        assertEquals(0, outbox.renewLease(row.getId(), "current", 120_000_000L));
        assertEquals(1, outbox.recoverExpired(row.getId(), "current"));
    }

    @Test
    void lockingReadsUseIdAsTieBreakerAndRespectLimit() {
        var first = insert(NOW);
        var second = insert(NOW);
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            assertEquals(List.of(first.getId()), outbox.selectDueForUpdate(1).stream().map(WebhookOutbox::getId).toList());
            assertEquals(List.of(first.getId(), second.getId()), outbox.selectDueForUpdate(2).stream().map(WebhookOutbox::getId).toList());
            assertEquals(1, outbox.claim(first.getId(), "first", 60_000_000L));
            assertEquals(1, outbox.claim(second.getId(), "second", 60_000_000L));
            jdbc.update("UPDATE webhook_outbox SET lease_expires_at = ? WHERE id IN (?, ?)", NOW, first.getId(), second.getId());
            assertEquals(List.of(first.getId()), outbox.selectExpiredForUpdate(1).stream().map(WebhookOutbox::getId).toList());
            assertEquals(List.of(first.getId(), second.getId()), outbox.selectExpiredForUpdate(2).stream().map(WebhookOutbox::getId).toList());
        });
    }

    @Test
    void stateTransitionsRequireCurrentTokenAndEnforceDueAndLease() {
        var row = insert(dbNow().plusDays(1));
        String first = UUID.randomUUID().toString();
        String second = UUID.randomUUID().toString();
        assertEquals(0, outbox.claim(row.getId(), first, 60_000_000L));
        jdbc.update("UPDATE webhook_outbox SET next_attempt_at = ? WHERE id = ?", NOW, row.getId());
        assertEquals(1, outbox.claim(row.getId(), first, 60_000_000L));
        assertEquals(0, outbox.claim(row.getId(), second, 60_000_000L));
        assertEquals(0, outbox.markDelivered(row.getId(), second));
        assertEquals(0, outbox.scheduleRetry(row.getId(), second, 1_000_000L, "retry"));
        assertEquals(0, outbox.markDead(row.getId(), second, "dead"));
        assertEquals(0, outbox.recoverExpired(row.getId(), first));
        expire(row.getId());
        assertEquals(0, outbox.recoverExpired(row.getId(), second));
        LocalDateTime beforeRecovery = dbNow();
        assertEquals(1, outbox.recoverExpired(row.getId(), first));
        var recovered = outbox.selectById(row.getId());
        assertEquals(0, recovered.getStatus());
        assertBetween(recovered.getNextAttemptAt(), beforeRecovery, dbNow());
        assertNull(recovered.getClaimToken());
        assertNull(recovered.getLeaseExpiresAt());
        assertEquals(1, outbox.claim(row.getId(), second, 60_000_000L));
        expire(row.getId());
        assertEquals(0, outbox.recoverExpired(row.getId(), first));
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL 1 HOUR WHERE id = ?", row.getId());
        assertEquals(0, outbox.markDelivered(row.getId(), first));
        assertEquals(0, outbox.scheduleRetry(row.getId(), first, 1_000_000L, "stale"));
        assertEquals(0, outbox.markDead(row.getId(), first, "stale"));
        LocalDateTime beforeRetry = dbNow();
        assertEquals(1, outbox.scheduleRetry(row.getId(), second, 30_000_000L, "transient"));
        var retry = outbox.selectById(row.getId());
        assertBetween(retry.getNextAttemptAt(), beforeRetry.plusSeconds(30), dbNow().plusSeconds(30));
        assertEquals("transient", retry.getLastFailureCode());
        assertNull(retry.getClaimToken());
        assertNull(retry.getLeaseExpiresAt());
        assertEquals(0, outbox.claim(row.getId(), first, 60_000_000L));
        jdbc.update("UPDATE webhook_outbox SET next_attempt_at = ? WHERE id = ?", NOW, row.getId());
        assertEquals(1, outbox.claim(row.getId(), first, 60_000_000L));
        LocalDateTime beforeDelivery = dbNow();
        assertEquals(1, outbox.markDelivered(row.getId(), first));
        var delivered = outbox.selectById(row.getId());
        assertEquals(2, delivered.getStatus());
        assertBetween(delivered.getDeliveredAt(), beforeDelivery, dbNow());
        assertNull(delivered.getLastFailureCode());
        assertNull(delivered.getClaimToken());
        assertNull(delivered.getLeaseExpiresAt());
        assertEquals(0, outbox.claim(row.getId(), first, 60_000_000L));
        var dead = insert(NOW);
        assertEquals(1, outbox.claim(dead.getId(), first, 60_000_000L));
        assertEquals(1, outbox.markDead(dead.getId(), first, "permanent"));
        var deadState = outbox.selectById(dead.getId());
        assertEquals(3, deadState.getStatus());
        assertEquals("permanent", deadState.getLastFailureCode());
        assertNull(deadState.getClaimToken());
        assertNull(deadState.getLeaseExpiresAt());
        assertEquals(0, outbox.claim(dead.getId(), first, 60_000_000L));
    }

    @Test
    void expiredLockReadSkipsAnotherTransactionsLockedLease() throws Exception {
        var first = insert(NOW);
        var second = insert(NOW);
        assertEquals(1, outbox.claim(first.getId(), "first", 60_000_000L));
        assertEquals(1, outbox.claim(second.getId(), "second", 60_000_000L));
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = ? WHERE id IN (?, ?)", NOW, first.getId(), second.getId());
        var pool = Executors.newSingleThreadExecutor();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> worker = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertEquals(List.of(first.getId()), outbox.selectExpiredForUpdate(1).stream().map(WebhookOutbox::getId).toList());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertEquals(1, outbox.recoverExpired(first.getId(), "first"));
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertEquals(List.of(second.getId()), outbox.selectExpiredForUpdate(10).stream().map(WebhookOutbox::getId).toList());
            });
            release.countDown();
            worker.get(10, TimeUnit.SECONDS);
            assertEquals(1, outbox.recoverExpired(second.getId(), "second"));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void lockingQueriesSkipHeldRowsAndOrderDueAndExpiredRows() throws Exception {
        var first = insert(NOW.minusSeconds(2));
        var second = insert(NOW.minusSeconds(1));
        var future = insert(dbNow().plusDays(1));
        var pool = Executors.newSingleThreadExecutor();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try {
            Future<?> worker = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var rows = outbox.selectDueForUpdate(1);
                assertEquals(List.of(first.getId()), rows.stream().map(WebhookOutbox::getId).toList());
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                assertEquals(1, outbox.claim(first.getId(), "first", 60_000_000L));
            }));
            assertTrue(locked.await(10, TimeUnit.SECONDS));
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                var rows = outbox.selectDueForUpdate(10);
                assertEquals(List.of(second.getId()), rows.stream().map(WebhookOutbox::getId).toList());
                assertEquals(1, outbox.claim(second.getId(), "second", 60_000_000L));
            });
            release.countDown();
            worker.get(10, TimeUnit.SECONDS);
            expire(first.getId());
            new TransactionTemplate(transactions).executeWithoutResult(status -> {
                assertTrue(outbox.selectDueForUpdate(10).isEmpty());
                assertEquals(List.of(first.getId()), outbox.selectExpiredForUpdate(10).stream().map(WebhookOutbox::getId).toList());
                assertEquals(1, outbox.recoverExpired(first.getId(), "first"));
            });
            assertEquals(0, outbox.claim(future.getId(), "future", 60_000_000L));
        } finally {
            release.countDown();
            pool.shutdownNow();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
    @Test
    void finalUpdatesRejectExpiredDatabaseLeaseEvenWithCurrentToken() {
        var row = insert(NOW);
        assertEquals(1, outbox.claim(row.getId(), "current", 60_000_000L));
        expire(row.getId());
        assertEquals(0, outbox.markDelivered(row.getId(), "current"));
        assertEquals(0, outbox.scheduleRetry(row.getId(), "current", 1_000_000L, "retry"));
        assertEquals(0, outbox.markDead(row.getId(), "current", "dead"));
        assertEquals(1, outbox.selectById(row.getId()).getStatus());
    }

    @ParameterizedTest
    @ValueSource(strings = {"delivered", "retry", "dead"})
    void completionRechecksDatabaseTimeAfterWaitingForRowLock(String outcome) throws Exception {
        var row = insert(NOW);
        assertEquals(1, outbox.claim(row.getId(), "current", 60_000_000L));
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) + INTERVAL 3 SECOND WHERE id = ?", row.getId());
        row = outbox.selectById(row.getId());
        final var claimed = row;
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertNotNull(outbox.lockForCompletion(claimed.getId()));
                locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var session = leaseManager.start(new ClaimedWebhookEvent(claimed, "current", claimed.getLeaseExpiresAt(), System.nanoTime(), Duration.ofMinutes(1).toNanos()));
            var result = pool.submit(() -> session.complete(() -> switch (outcome) {
                case "delivered" -> outbox.markDelivered(claimed.getId(), "current");
                case "retry" -> outbox.scheduleRetry(claimed.getId(), "current", 1_000_000L, "retry");
                case "dead" -> outbox.markDead(claimed.getId(), "current", "dead");
                default -> throw new IllegalArgumentException(outcome);
            }));
            assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS));
            Thread.sleep(3500);
            release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertEquals(LeaseStatus.LEASE_LOST, result.get(5, TimeUnit.SECONDS));
            assertEquals(1, outbox.selectById(claimed.getId()).getStatus());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    @Test
    void renewalRejectsLeaseThatExpiresWhileWaitingForLock() throws Exception {
        var row = insert(NOW);
        assertEquals(1, outbox.claim(row.getId(), "current", 3_000_000L));
        LocalDateTime expiry = outbox.selectById(row.getId()).getLeaseExpiresAt();
        var locked = new CountDownLatch(1); var release = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                assertNotNull(outbox.lockForCompletion(row.getId())); locked.countDown();
                try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }));
            assertTrue(locked.await(5, TimeUnit.SECONDS));
            var result = pool.submit(() -> new TransactionTemplate(transactions).execute(tx -> {
                assertNotNull(outbox.lockForCompletion(row.getId()));
                return outbox.renewLease(row.getId(), "current", 60_000_000L);
            }));
            assertThrows(TimeoutException.class, () -> result.get(200, TimeUnit.MILLISECONDS));
            Thread.sleep(3500); release.countDown(); holder.get(5, TimeUnit.SECONDS);
            assertEquals(0, result.get(5, TimeUnit.SECONDS));
            assertEquals(expiry, outbox.selectById(row.getId()).getLeaseExpiresAt());
        } finally { release.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }

    private LocalDateTime dbNow() {
        return jdbc.queryForObject("SELECT UTC_TIMESTAMP(6)", LocalDateTime.class);
    }

    private void expire(long id) {
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
    }

    private void assertBetween(LocalDateTime actual, LocalDateTime lower, LocalDateTime upper) {
        assertNotNull(actual);
        // DATETIME(3) stores only millisecond precision.
        assertFalse(actual.isBefore(lower.minusNanos(999_000)), () -> actual + " before " + lower);
        assertFalse(actual.isAfter(upper.plusNanos(999_000)), () -> actual + " after " + upper);
    }

    @ParameterizedTest
    @ValueSource(longs = {-315360000L, 315360000L})
    void completionDoesNotDependOnJavaWallClock(long offsetSeconds) {
        var row = insert(NOW);
        assertEquals(1, outbox.claim(row.getId(), "current", 60_000_000L));
        var claimed = outbox.selectById(row.getId());
        Clock skewed = Clock.offset(Clock.systemUTC(), Duration.ofSeconds(offsetSeconds));
        try (var manager = new WebhookLeaseManager(outbox, leaseScheduler, skewed,
                Duration.ofMinutes(1), Duration.ofSeconds(30), new TransactionTemplate(transactions))) {
            var event = new ClaimedWebhookEvent(claimed, "current", claimed.getLeaseExpiresAt(),
                    System.nanoTime(), Duration.ofMinutes(1).toNanos());
            try (var session = manager.start(event)) {
                assertEquals(LeaseStatus.COMPLETED, session.complete(() -> outbox.markDelivered(row.getId(), "current")));
            }
        }
        assertEquals(2, outbox.selectById(row.getId()).getStatus());
    }

}
