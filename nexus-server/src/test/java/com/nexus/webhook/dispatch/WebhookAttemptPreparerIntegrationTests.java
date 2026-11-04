package com.nexus.webhook.dispatch;

import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static com.nexus.webhook.dispatch.WebhookAttemptPreparer.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "nexus.webhook.scheduling.enabled=false")
class WebhookAttemptPreparerIntegrationTests {
    @Autowired WebhookOutboxMapper outbox;
    @Autowired UserMapper users;
    @Autowired ApplicationMapper applications;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    User owner;
    Application application;
    WebhookAttemptPreparer preparer;

    @BeforeEach void setup() {
        assertEquals("nexus_test", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertTrue(jdbc.queryForObject("SELECT CURRENT_USER()", String.class).startsWith("nexus_test_user@"));
        owner = new User(); owner.setEmail(UUID.randomUUID() + "@example.test");
        owner.setDisplayName("attempt test"); owner.setPasswordHash("test-only-placeholder"); users.insert(owner);
        application = new Application(); application.setOwnerUserId(owner.getId());
        application.setName("attempt-" + UUID.randomUUID()); applications.insert(application);
        preparer = new WebhookAttemptPreparer(outbox, new TransactionTemplate(transactions), 2);
    }
    @AfterEach void cleanup() {
        if (application != null && application.getId() != null) {
            jdbc.update("DELETE FROM webhook_outbox WHERE application_id = ?", application.getId());
            applications.deleteById(application.getId());
        }
        if (owner != null && owner.getId() != null) users.deleteById(owner.getId());
    }
    private long claimed(String token) {
        var row = new WebhookOutbox(); row.setEventId(UUID.randomUUID().toString());
        row.setJobId(UUID.randomUUID().toString()); row.setEventType("screenshot.completed");
        row.setApplicationId(application.getId()); row.setEventStatus(0);
        row.setCompletedAt(LocalDateTime.of(1901, 1, 1, 0, 0)); row.setQueryUrl("https://example.test/job");
        row.setStatus(0); row.setAttemptCount(0L); row.setNextAttemptAt(LocalDateTime.of(1901, 1, 1, 0, 0));
        outbox.insert(row); assertEquals(1, outbox.claim(row.getId(), token, 300_000_000L)); return row.getId();
    }
    private Snapshot state(long id) {
        return jdbc.queryForObject("SELECT attempt_count, secret_refresh_attempted FROM webhook_outbox WHERE id = ?",
                (rs, n) -> new Snapshot(rs.getLong(1), rs.getBoolean(2)), id);
    }
    @Test void schemaHasDefaultAndEnforcedBooleanConstraint() {
        long id = claimed("schema");
        assertEquals(new Snapshot(0, false), state(id));
        var column = jdbc.queryForMap("SELECT IS_NULLABLE, COLUMN_DEFAULT, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'webhook_outbox' AND COLUMN_NAME = 'secret_refresh_attempted'");
        assertEquals("NO", column.get("IS_NULLABLE"));
        assertEquals("0", String.valueOf(column.get("COLUMN_DEFAULT")));
        assertTrue(String.valueOf(column.get("COLUMN_TYPE")).contains("unsigned"));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE webhook_outbox SET attempt_count = 4, secret_refresh_attempted = 2 WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE webhook_outbox SET secret_refresh_attempted = NULL WHERE id = ?", id));
        assertThrows(DataAccessException.class, () -> jdbc.update("UPDATE webhook_outbox SET attempt_count = 1, secret_refresh_attempted = 1 WHERE id = ?", id));
        assertEquals(new Snapshot(0, false), state(id));
    }
    @Test void initialOrdinaryAndSingleSecretRefreshSurviveReclaim() {
        long id = claimed("first");
        Snapshot initial = new Snapshot(0, false);
        assertTrue(preparer.prepare(id, "first", initial, AttemptType.ORDINARY_RETRY).isEmpty());
        assertTrue(preparer.prepare(id, "first", initial, AttemptType.SECRET_REFRESH).isEmpty());
        var one = preparer.prepare(id, "first", initial, AttemptType.INITIAL).orElseThrow();
        assertEquals(new Snapshot(1, false), one);
        assertTrue(preparer.prepare(id, "first", one, AttemptType.INITIAL).isEmpty());
        var two = preparer.prepare(id, "first", one, AttemptType.ORDINARY_RETRY).orElseThrow();
        assertEquals(new Snapshot(2, false), two);
        var refresh = preparer.prepare(id, "first", two, AttemptType.SECRET_REFRESH).orElseThrow();
        assertEquals(new Snapshot(3, true), refresh);
        assertTrue(preparer.prepare(id, "first", refresh, AttemptType.SECRET_REFRESH).isEmpty());
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        assertEquals(1, outbox.recoverExpired(id, "first"));
        assertEquals(1, outbox.claim(id, "second", 300_000_000L));
        assertEquals(refresh, state(id));
        assertTrue(preparer.prepare(id, "second", refresh, AttemptType.SECRET_REFRESH).isEmpty());
        var last = preparer.prepare(id, "second", refresh, AttemptType.ORDINARY_RETRY).orElseThrow();
        assertEquals(new Snapshot(4, true), last);
        assertTrue(preparer.prepare(id, "second", last, AttemptType.ORDINARY_RETRY).isEmpty());
        assertEquals(last, state(id));
    }
    @Test void staleSnapshotOldTokenAndExpiredLeaseCannotIssuePermit() {
        long id = claimed("old"); var zero = new Snapshot(0, false);
        var one = preparer.prepare(id, "old", zero, AttemptType.INITIAL).orElseThrow();
        assertTrue(preparer.prepare(id, "old", zero, AttemptType.INITIAL).isEmpty());
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        assertTrue(preparer.prepare(id, "old", one, AttemptType.ORDINARY_RETRY).isEmpty());
        assertEquals(1, outbox.recoverExpired(id, "old")); assertEquals(1, outbox.claim(id, "new", 300_000_000L));
        assertTrue(preparer.prepare(id, "old", one, AttemptType.ORDINARY_RETRY).isEmpty());
        assertEquals(one, state(id));
    }
    @Test void concurrentIdenticalSnapshotsHaveExactlyOneWinner() throws Exception {
        long id = claimed("race"); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> call = () -> { ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
                return preparer.prepare(id, "race", new Snapshot(0, false), AttemptType.INITIAL).isPresent(); };
            var first = pool.submit(call); var second = pool.submit(call);
            assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
            assertNotEquals(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(new Snapshot(1, false), state(id));
        } finally { start.countDown(); pool.shutdownNow(); assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS)); }
    }
    @Test void rolledBackWriteReturnsNoPermitAndPreservesCounter() {
        long id = claimed("rollback");
        PlatformTransactionManager failing = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition d) { return transactions.getTransaction(d); }
            public void commit(TransactionStatus s) { transactions.rollback(s); throw new IllegalStateException("commit failed"); }
            public void rollback(TransactionStatus s) { transactions.rollback(s); }
        };
        var gate = new WebhookAttemptPreparer(outbox, new TransactionTemplate(failing), 2);
        assertThrows(IllegalStateException.class, () -> gate.prepare(id, "rollback", new Snapshot(0, false), AttemptType.INITIAL));
        assertEquals(new Snapshot(0, false), state(id));
    }
    @Test void committedWriteWithLostAcknowledgementReturnsNoPermit() {
        long id = claimed("ambiguous");
        PlatformTransactionManager ambiguous = new PlatformTransactionManager() {
            public TransactionStatus getTransaction(TransactionDefinition d) { return transactions.getTransaction(d); }
            public void commit(TransactionStatus s) { transactions.commit(s); throw new IllegalStateException("ack lost"); }
            public void rollback(TransactionStatus s) { transactions.rollback(s); }
        };
        var gate = new WebhookAttemptPreparer(outbox, new TransactionTemplate(ambiguous), 2);
        assertThrows(IllegalStateException.class, () -> gate.prepare(id, "ambiguous", new Snapshot(0, false), AttemptType.INITIAL));
        assertEquals(new Snapshot(1, false), state(id));
        assertTrue(preparer.prepare(id, "ambiguous", new Snapshot(0, false), AttemptType.INITIAL).isEmpty());
    }
    @Test void failedSendKeepsCommittedCountAndStaleAdmissionDoesNotSend() {
        long id = claimed("send");
        var sends = new java.util.concurrent.atomic.AtomicInteger();
        var original = new java.io.IOException("simulated send failure");
        var failure = assertThrows(WebhookSendException.class, () -> preparer.prepareAndSend(id, "send", new Snapshot(0, false), AttemptType.INITIAL, committed -> {
            sends.incrementAndGet();
            assertEquals(new Snapshot(1, false), state(id));
            assertEquals(new Snapshot(1, false), committed);
            throw original;
        }));
        assertSame(original, failure.getCause());
        assertEquals(new Snapshot(1, false), failure.snapshot());
        assertEquals(new Snapshot(1, false), state(id));
        assertDoesNotThrow(() -> assertTrue(preparer.prepareAndSend(id, "send", new Snapshot(0, false), AttemptType.INITIAL, committed -> {
            sends.incrementAndGet(); return "unexpected";
        }).isEmpty()));
        assertEquals(1, sends.get());
        jdbc.update("UPDATE webhook_outbox SET lease_expires_at = UTC_TIMESTAMP(6) - INTERVAL 1 SECOND WHERE id = ?", id);
        assertEquals(1, outbox.recoverExpired(id, "send"));
        assertEquals(1, outbox.claim(id, "reclaimed", 300_000_000L));
        assertEquals(failure.snapshot(), state(id));
        assertEquals(new Snapshot(2, false), preparer.prepare(id, "reclaimed", failure.snapshot(), AttemptType.ORDINARY_RETRY).orElseThrow());
    }
    @Test void refusesToIssuePermitInsideExistingTransaction() {
        long id = claimed("outer");
        new TransactionTemplate(transactions).executeWithoutResult(tx ->
                assertThrows(IllegalStateException.class, () -> preparer.prepare(id, "outer", new Snapshot(0, false), AttemptType.INITIAL)));
        assertEquals(new Snapshot(0, false), state(id));
    }
}
