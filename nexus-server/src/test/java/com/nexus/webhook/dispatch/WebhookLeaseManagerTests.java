package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.function.LongSupplier;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookLeaseManagerTests {
    static final class MovingClock implements LongSupplier {
        private long nanos;
        public long getAsLong() { return nanos; }
        void advance(long seconds) { nanos += TimeUnit.SECONDS.toNanos(seconds); }
    }
    private final MovingClock ticker = new MovingClock();
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final LocalDateTime now = LocalDateTime.now(clock);
    private final WebhookOutboxMapper mapper = mock(WebhookOutboxMapper.class);
    private final LeaseScheduler scheduler = new LeaseScheduler();
    private final PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
    private final TransactionTemplate transaction = new TransactionTemplate(txManager);
    private final WebhookLeaseManager manager = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, ticker);
    private ClaimedWebhookEvent event(long id) {
        var row = new WebhookOutbox(); row.setId(id); row.setEventId("event-" + id);
        return new ClaimedWebhookEvent(row, "token", now.plusSeconds(60), 0, TimeUnit.SECONDS.toNanos(60));
    }
    @BeforeEach void setupTransaction() {
        when(mapper.lockForCompletion(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(txManager.getTransaction(any())).thenAnswer(inv -> new SimpleTransactionStatus());
        when(mapper.selectLeaseSnapshot(anyLong(), anyString())).thenReturn(new LeaseSnapshot(now.plusSeconds(60), now));
    }
    @AfterEach void close() { manager.close(); scheduler.close(); }

    @Test void successExtendsLeaseWithUtcTimes() {
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenReturn(1);
        var s = manager.start(event(1)); s.renew();
        verify(mapper).renewLease(1, "token", 60_000_000L);
        assertEquals(LeaseStatus.ACTIVE, s.status());
    }
    @Test void zeroRowsLosesLeaseAndCancelsOnce() {
        var s = manager.start(event(1)); var calls = new AtomicInteger();
        s.cancellation().onCancel(calls::incrementAndGet); s.renew(); s.renew(); s.close();
        assertEquals(LeaseStatus.LEASE_LOST, s.status()); assertEquals(1, calls.get());
        verify(mapper, times(1)).renewLease(anyLong(), anyString(), anyLong());
    }
    @Test void finiteRetriesThenUncertain() {
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenThrow(new IllegalStateException("sensitive"));
        var s = manager.start(event(1)); s.renew();
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status()); assertTrue(s.cancellation().isCancelled());
        verify(mapper, times(3)).renewLease(anyLong(), anyString(), anyLong());
    }
    @Test void transientErrorCanRecover() {
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenThrow(new IllegalStateException()).thenReturn(1);
        var s = manager.start(event(1)); s.renew(); assertEquals(LeaseStatus.ACTIVE, s.status());
        verify(mapper, times(2)).renewLease(anyLong(), anyString(), anyLong());
    }
    @Test void completionStopsRenewalAndDoesNotCancel() {
        var s = manager.start(event(1)); assertEquals(LeaseStatus.COMPLETED, s.complete(() -> 1));
        s.renew(); assertFalse(s.cancellation().isCancelled()); verify(mapper, never()).renewLease(anyLong(), anyString(), anyLong());
    }
    @Test void failedWritebackIsUncertainAndThrows() {
        var s = manager.start(event(1)); assertThrows(IllegalStateException.class, () -> s.complete(() -> { throw new IllegalStateException(); }));
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status()); assertTrue(s.cancellation().isCancelled());
    }
    @Test void zeroWritebackLosesLeaseAndNoSecondWrite() {
        var s = manager.start(event(1)); assertEquals(LeaseStatus.LEASE_LOST, s.complete(() -> 0));
        assertEquals(LeaseStatus.LEASE_LOST, s.complete(() -> { fail(); return 1; }));
    }
    @Test void alreadyExpiredCannotWriteOrRenew() {
        var event = event(1); var s = manager.start(new ClaimedWebhookEvent(event.event(), event.claimToken(), now, 0, 0));
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.complete(() -> { fail(); return 1; })); verifyNoInteractions(mapper);
    }
    @Test void independentSessionsAndShutdown() {
        var a = manager.start(event(1)); var b = manager.start(event(2)); a.renew();
        assertEquals(LeaseStatus.LEASE_LOST, a.status()); assertEquals(LeaseStatus.ACTIVE, b.status());
        manager.close(); assertEquals(LeaseStatus.LEASE_UNCERTAIN, b.status());
        assertThrows(IllegalStateException.class, () -> manager.start(event(3)));
    }
    @Test void lateCancellationCallbackRunsAndExceptionsAreIsolated() {
        var s = manager.start(event(1)); s.cancellation().onCancel(() -> { throw new IllegalStateException(); });
        s.close(); var n = new AtomicInteger(); s.cancellation().onCancel(n::incrementAndGet); assertEquals(1, n.get());
    }
    @Test void renewalAndWritebackDoNotOverlap() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 1;
        });
        var s = manager.start(event(1)); var pool = Executors.newFixedThreadPool(2);
        try {
            var renew = pool.submit(s::renew); assertTrue(entered.await(5, TimeUnit.SECONDS));
            var write = pool.submit(() -> s.complete(() -> 1));
            assertThrows(TimeoutException.class, () -> write.get(100, TimeUnit.MILLISECONDS));
            release.countDown(); renew.get(5, TimeUnit.SECONDS); assertEquals(LeaseStatus.COMPLETED, write.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void rejectInvalidDurations() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookLeaseManager(mapper, scheduler, clock, Duration.ofSeconds(20), Duration.ofSeconds(20), transaction));
    }
    @Test void futureRegisteredAfterTerminationIsCancelled() {
        var s = manager.start(event(1)); s.close();
        var late = mock(ScheduledFuture.class); s.bindFuture(late);
        verify(late).cancel(false);
    }
    @Test void schedulerErrorAfterDeadlineRegistrationCancelsDeadline() {
        var failingScheduler = mock(LeaseScheduler.class);
        var deadline = mock(ScheduledFuture.class);
        when(failingScheduler.deadline(any(), anyLong())).thenReturn(deadline);
        when(failingScheduler.schedule(any(), any())).thenThrow(new AssertionError("failed"));
        try (var other = new WebhookLeaseManager(mapper, failingScheduler,
                Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, ticker)) {
            assertThrows(AssertionError.class, () -> other.start(event(1)));
            verify(deadline).cancel(false);
        }
    }
    @Test void unexpectedMonotonicClockFailureStopsLease() {
        var calls = new AtomicInteger();
        LongSupplier broken = () -> { if (calls.incrementAndGet() > 1) throw new IllegalStateException(); return 0; };
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, broken)) {
            var s = other.start(event(1)); s.renew();
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status()); assertTrue(s.cancellation().isCancelled());
        }
    }
    @Test void clockFailureWhileArmingRenewedDeadlineCancels() {
        var calls = new AtomicInteger();
        LongSupplier broken = () -> { if (calls.incrementAndGet() == 5) throw new IllegalStateException(); return 0; };
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenReturn(1);
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, broken)) {
            var s = other.start(event(1));
            s.renew();
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
            assertTrue(s.cancellation().isCancelled());
        }
    }

    @Test void invalidRenewalSnapshotCannotExtendOldBudget() {
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
            ticker.advance(30);
            return 1;
        });
        when(mapper.selectLeaseSnapshot(anyLong(), anyString())).thenReturn(new LeaseSnapshot(null, now));
        var s = manager.start(event(1));
        s.renew();
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        verify(mapper, times(2)).renewLease(anyLong(), anyString(), anyLong());
    }
    @Test void schedulerErrorAndWritebackErrorBothLeaveActiveState() {
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenThrow(new AssertionError("private detail"));
        var renewing = manager.start(event(1)); renewing.renew();
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, renewing.status());
        assertTrue(renewing.cancellation().isCancelled());
        var writing = manager.start(event(2));
        assertThrows(AssertionError.class, () -> writing.complete(() -> { throw new AssertionError(); }));
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, writing.status());
        assertTrue(writing.cancellation().isCancelled());
    }
    @Test void retryUsesFreshTimeAndStopsWhenLeaseExpires() {
        var moving = new MovingClock();
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, moving)) {
            when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
                moving.advance(10); throw new IllegalStateException();
            }).thenReturn(1);
            var s = other.start(event(1)); s.renew();
            verify(mapper, times(2)).renewLease(1, "token", 60_000_000L);
            assertEquals(LeaseStatus.ACTIVE, s.status());
        }
        reset(mapper);
        when(mapper.lockForCompletion(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        var expiryClock = new MovingClock();
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, expiryClock)) {
            when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
                expiryClock.advance(60); throw new IllegalStateException();
            });
            var s = other.start(event(2)); s.renew();
            verify(mapper, times(1)).renewLease(anyLong(), anyString(), anyLong());
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        }
    }
    @Test void renewalReturningAfterItsNewExpiryBecomesUncertain() {
        var moving = new MovingClock();
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, moving)) {
            when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> { moving.advance(60); return 1; });
            var s = other.start(event(1)); s.renew();
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        }
    }
    @Test void callbackAssertionDoesNotPreventOtherNotifications() {
        var s = manager.start(event(1)); var calls = new AtomicInteger();
        s.cancellation().onCancel(() -> { throw new AssertionError("private detail"); });
        s.cancellation().onCancel(calls::incrementAndGet);
        s.close(); assertEquals(1, calls.get());
    }
    @Test void closeDoesNotWaitForBlockedRenewalAndStopsOtherSessions() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(mapper.renewLease(eq(1L), anyString(), anyLong())).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return 1;
        });
        var a = manager.start(event(1)); var b = manager.start(event(2));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var blocked = pool.submit(a::renew); assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.close();
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, a.status()); assertTrue(a.cancellation().isCancelled());
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, b.status()); assertTrue(b.cancellation().isCancelled());
            release.countDown(); blocked.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void closeDuringCommittedWriteCancelsPromptlyAndKeepsCommittedResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var a = manager.start(event(1)); var b = manager.start(event(2));
        var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> a.complete(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return 1;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            manager.close();
            assertTrue(a.cancellation().isCancelled());
            assertTrue(b.cancellation().isCancelled());
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, a.status());
            release.countDown();
            assertEquals(LeaseStatus.COMPLETED, result.get(5, TimeUnit.SECONDS));
            assertEquals(LeaseStatus.COMPLETED, a.status());
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    @Test void sessionCloseDuringZeroRowWriteKeepsLostResult() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var s = manager.start(event(1)); var pool = Executors.newSingleThreadExecutor();
        try {
            var result = pool.submit(() -> s.complete(() -> {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                return 0;
            }));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            s.close(); assertTrue(s.cancellation().isCancelled());
            release.countDown(); assertEquals(LeaseStatus.LEASE_LOST, result.get(5, TimeUnit.SECONDS));
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void transactionCommitFailureAfterWriteIsUncertain() {
        doThrow(new IllegalStateException("commit failed")).when(txManager).commit(any());
        var s = manager.start(event(1));
        assertThrows(IllegalStateException.class, () -> s.complete(() -> 1));
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        assertTrue(s.cancellation().isCancelled());
    }
    @Test void closeBeforeCompletionPreventsWriteback() {
        var s = manager.start(event(1)); s.close();
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.complete(() -> { fail("writeback ran after close"); return 1; }));
        verify(mapper, never()).lockForCompletion(anyLong());
    }

    @Test void closeBeforeRenewalAdmissionPreventsDatabaseAttempt() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        LongSupplier blockedClock = () -> {
            if (calls.incrementAndGet() > 1) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            }
            return 0;
        };
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(60), Duration.ofSeconds(20), transaction, blockedClock)) {
            var s = other.start(event(1)); var pool = Executors.newSingleThreadExecutor();
            try {
                var renew = pool.submit(s::renew); assertTrue(entered.await(5, TimeUnit.SECONDS));
                s.close(); assertTrue(s.cancellation().isCancelled());
                release.countDown(); renew.get(5, TimeUnit.SECONDS);
                assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
                verify(mapper, never()).lockForCompletion(anyLong());
                verify(mapper, never()).renewLease(anyLong(), anyString(), anyLong());
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }

    @Test void closeDuringFailedRenewalPreventsRetry() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS));
            throw new IllegalStateException();
        });
        var s = manager.start(event(1)); var pool = Executors.newSingleThreadExecutor();
        try {
            var renew = pool.submit(s::renew); assertTrue(entered.await(5, TimeUnit.SECONDS));
            s.close(); assertTrue(s.cancellation().isCancelled()); release.countDown();
            renew.get(5, TimeUnit.SECONDS);
            assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
            verify(mapper, times(1)).renewLease(anyLong(), anyString(), anyLong());
        } finally { release.countDown(); pool.shutdownNow(); }
    }

    @Test void requiresTransactionForLeaseOperations() {
        for (int propagation : new int[] {TransactionDefinition.PROPAGATION_SUPPORTS,
                TransactionDefinition.PROPAGATION_NOT_SUPPORTED, TransactionDefinition.PROPAGATION_NEVER,
                TransactionDefinition.PROPAGATION_MANDATORY, TransactionDefinition.PROPAGATION_NESTED}) {
            var template = new TransactionTemplate(); template.setPropagationBehavior(propagation);
            assertThrows(IllegalArgumentException.class, () -> new WebhookLeaseManager(
                    mapper, scheduler, clock, Duration.ofSeconds(60), Duration.ofSeconds(20), template));
        }
        var required = new TransactionTemplate();
        var requiresNew = new TransactionTemplate();
        requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        assertDoesNotThrow(() -> new WebhookLeaseManager(mapper, scheduler, clock,
                Duration.ofSeconds(60), Duration.ofSeconds(20), required));
        assertDoesNotThrow(() -> new WebhookLeaseManager(mapper, scheduler, clock,
                Duration.ofSeconds(60), Duration.ofSeconds(20), requiresNew));
    }

    @Test void renewalInsideOuterTransactionIsUncertainWithoutDatabaseAccess() {
        var s = manager.start(event(1));
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { s.renew(); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        assertTrue(s.cancellation().isCancelled());
        verifyNoInteractions(mapper);
    }

    @Test void expiredBeforeWorkerStartsCancelsImmediately() {
        ticker.advance(60);
        var s = manager.start(event(1));
        assertEquals(LeaseStatus.LEASE_UNCERTAIN, s.status());
        assertTrue(s.cancellation().isCancelled());
        verifyNoInteractions(mapper);
    }

    @Test void deadlineStillCancelsWhenAllRenewalThreadsAreBlocked() throws Exception {
        var entered = new CountDownLatch(4);
        var release = new CountDownLatch(1);
        var lateSnapshots = new CountDownLatch(4);
        when(mapper.renewLease(anyLong(), anyString(), anyLong())).thenAnswer(inv -> {
            entered.countDown();
            assertTrue(release.await(8, TimeUnit.SECONDS));
            return 1;
        });
        when(mapper.selectLeaseSnapshot(anyLong(), anyString())).thenAnswer(inv -> {
            lateSnapshots.countDown();
            return new LeaseSnapshot(now.plusSeconds(60), now);
        });
        try (var other = new WebhookLeaseManager(mapper, scheduler, Duration.ofSeconds(5),
                Duration.ofMillis(30), transaction, System::nanoTime)) {
            var sessions = new java.util.ArrayList<WebhookLeaseManager.Session>();
            var cancelled = new CountDownLatch(4);
            for (long i = 0; i < 4; i++) {
                var row = event(i).event();
                var claim = new ClaimedWebhookEvent(row, "token", now.plusSeconds(3),
                        System.nanoTime(), TimeUnit.SECONDS.toNanos(3));
                var session = other.start(claim);
                session.cancellation().onCancel(cancelled::countDown);
                sessions.add(session);
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(cancelled.await(5, TimeUnit.SECONDS));
            assertTrue(sessions.stream().allMatch(s -> s.status() == LeaseStatus.LEASE_UNCERTAIN));
            release.countDown();
            assertTrue(lateSnapshots.await(5, TimeUnit.SECONDS));
            for (var session : sessions) {
                assertEquals(LeaseStatus.LEASE_UNCERTAIN,
                        session.complete(() -> { fail("late renewal revived the lease"); return 1; }));
            }
        } finally { release.countDown(); }
    }

    @Test void rejectsSubMicrosecondAndOverflowingLeaseDurations() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookLeaseManager(mapper, scheduler,
                Duration.ofNanos(1), Duration.ofNanos(1), transaction, ticker));
        assertThrows(IllegalArgumentException.class, () -> new WebhookLeaseManager(mapper, scheduler,
                Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(1), transaction, ticker));
    }

}
