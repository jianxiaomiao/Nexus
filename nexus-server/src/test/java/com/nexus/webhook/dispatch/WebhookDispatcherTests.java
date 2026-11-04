package com.nexus.webhook.dispatch;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nexus.webhook.entity.WebhookOutbox;
import com.nexus.webhook.mapper.WebhookOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WebhookDispatcherTests {
    private final WebhookOutboxMapper mapper = mock(WebhookOutboxMapper.class);
    private final TransactionTemplate transaction = mock(TransactionTemplate.class);
    private final ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
    private final WebhookWorker worker = mock(WebhookWorker.class);
    private final Semaphore semaphore = new Semaphore(2);
    private final Clock clock = Clock.fixed(Instant.parse("2026-11-01T01:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final LocalDateTime now = LocalDateTime.parse("2026-11-01T01:00:00");
    private final AtomicBoolean committed = new AtomicBoolean();
    private WebhookDispatcher dispatcher;

    @BeforeEach
    void setup() {
        when(executor.getRejectedExecutionHandler()).thenReturn(new ThreadPoolExecutor.AbortPolicy());
        when(transaction.getPropagationBehavior()).thenReturn(TransactionDefinition.PROPAGATION_REQUIRED);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            Object result = ((TransactionCallback<?>) invocation.getArgument(0)).doInTransaction(null);
            committed.set(true);
            return result;
        });
        when(mapper.claim(anyLong(), anyString(), anyLong())).thenReturn(1);
        when(mapper.selectLeaseSnapshot(anyLong(), anyString())).thenReturn(new LeaseSnapshot(now.plusMinutes(2), now));
        dispatcher = create();
    }

    private WebhookDispatcher create() {
        return new WebhookDispatcher(mapper, transaction, executor, semaphore, clock, Duration.ofMinutes(2), worker);
    }
    private WebhookOutbox event(long id) {
        WebhookOutbox event = new WebhookOutbox();
        event.setId(id); event.setEventId("event-" + id);
        return event;
    }
    private void events(WebhookOutbox... events) { when(mapper.selectDueForUpdate(anyInt())).thenReturn(List.of(events)); }
    private void inline() { doAnswer(i -> { assertTrue(committed.get()); ((Runnable) i.getArgument(0)).run(); return null; }).when(executor).execute(any()); }

    @Test void noCapacityDoesNotQuery() {
        semaphore.acquireUninterruptibly(2);
        dispatcher.dispatchDueEvents();
        verifyNoInteractions(mapper);
    }
    @Test void reservesCapacityAndUsesUtc() {
        events(event(1)); inline(); dispatcher.dispatchDueEvents();
        verify(mapper).selectDueForUpdate(2);
        verify(mapper).claim(eq(1L), anyString(), eq(120_000_000L));
        assertEquals(2, semaphore.availablePermits());
    }
    @Test void emptyBatchReleasesAll() {
        events(); dispatcher.dispatchDueEvents();
        assertEquals(2, semaphore.availablePermits()); verify(executor, never()).execute(any());
    }
    @Test void fewerClaimsReleaseUnusedAndKeepSubmittedPermit() {
        events(event(1)); dispatcher.dispatchDueEvents();
        assertEquals(1, semaphore.availablePermits()); verify(executor).execute(any());
    }
    @Test void onlySuccessfulClaimIsSubmittedAndTokensDiffer() {
        WebhookOutbox first = event(1), second = event(2); events(first, second);
        when(mapper.claim(eq(2L), anyString(), anyLong())).thenReturn(0);
        inline(); dispatcher.dispatchDueEvents();
        var tokens = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(mapper, times(2)).claim(anyLong(), tokens.capture(), eq(120_000_000L));
        assertNotEquals(tokens.getAllValues().get(0), tokens.getAllValues().get(1));
        verify(worker).process(argThat(e -> e.event() == first && e.claimToken().equals(first.getClaimToken())));
        assertNull(second.getClaimToken()); assertEquals(2, semaphore.availablePermits());
    }

    @Test void stopPreventsSubmissionAfterInFlightClaimCompletes() throws Exception {
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        events(event(1));
        when(mapper.releaseClaim(eq(1L), anyString())).thenReturn(1);
        doAnswer(inv -> {
            var result = ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null);
            entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); return result;
        }).when(transaction).execute(any());
        var thread = new Thread(dispatcher::dispatchDueEvents);
        thread.start();
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            dispatcher.stopAccepting();
        } finally { release.countDown(); thread.join(5000); }
        assertFalse(thread.isAlive());
        verify(executor, never()).execute(any());
        verify(mapper).releaseClaim(eq(1L), anyString());
        assertEquals(2, semaphore.availablePermits());
    }

    @Test void claimBudgetStartsBeforeTransactionAndUsesStoredSnapshot() {
        var ticks = new AtomicLong();
        var storedExpiry = now.plusSeconds(41).plusNanos(123_000_000);
        when(mapper.selectLeaseSnapshot(anyLong(), anyString())).thenAnswer(inv -> {
            ticks.addAndGet(TimeUnit.SECONDS.toNanos(2));
            return new LeaseSnapshot(storedExpiry, now.plusSeconds(1));
        });
        doAnswer(inv -> {
            Object result = ((TransactionCallback<?>) inv.getArgument(0)).doInTransaction(null);
            ticks.addAndGet(TimeUnit.SECONDS.toNanos(3));
            committed.set(true);
            return result;
        }).when(transaction).execute(any());
        events(event(1)); inline();
        var timed = new WebhookDispatcher(mapper, transaction, executor, semaphore,
                Duration.ofMinutes(2), worker, ticks::get);
        timed.dispatchDueEvents();
        var captured = org.mockito.ArgumentCaptor.forClass(ClaimedWebhookEvent.class);
        verify(worker).process(captured.capture());
        var claim = captured.getValue();
        assertEquals(storedExpiry, claim.leaseExpiresAt());
        assertEquals(0, claim.budgetStartedNanos());
        assertEquals(Duration.between(now.plusSeconds(1), storedExpiry).toNanos(), claim.budgetNanos());
        assertEquals(TimeUnit.SECONDS.toNanos(5), ticks.get() - claim.budgetStartedNanos());
    }
    @Test void transactionFailureReleasesPermitsAndPropagates() {
        doThrow(new IllegalStateException("failure")).when(transaction).execute(any());
        assertThrows(IllegalStateException.class, dispatcher::dispatchDueEvents);
        assertEquals(2, semaphore.availablePermits()); verify(executor, never()).execute(any());
    }
    @Test void commitFailureAfterClaimNeverSubmits() {
        events(event(1));
        doAnswer(i -> { ((TransactionCallback<?>) i.getArgument(0)).doInTransaction(null); throw new IllegalStateException("commit failed"); }).when(transaction).execute(any());
        assertThrows(IllegalStateException.class, dispatcher::dispatchDueEvents);
        assertEquals(2, semaphore.availablePermits()); verify(executor, never()).execute(any());
    }
    @Test void rejectionReleasesClaimWithUtcAndContinues() {
        events(event(1), event(2));
        doThrow(new RejectedExecutionException()).doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); return null; }).when(executor).execute(any());
        when(mapper.releaseClaim(eq(1L), anyString())).thenReturn(1);
        dispatcher.dispatchDueEvents();
        verify(mapper).releaseClaim(eq(1L), anyString());
        verify(worker).process(argThat(e -> e.event().getId() == 2)); assertEquals(2, semaphore.availablePermits());
    }
    @Test void releaseFailureDoesNotStopNextEvent() {
        events(event(1), event(2));
        doThrow(new RejectedExecutionException()).when(executor).execute(any());
        when(mapper.releaseClaim(eq(1L), anyString())).thenThrow(new IllegalStateException());
        when(mapper.releaseClaim(eq(2L), anyString())).thenReturn(0);
        dispatcher.dispatchDueEvents();
        verify(mapper, times(2)).releaseClaim(anyLong(), anyString()); assertEquals(2, semaphore.availablePermits());
    }
    @Test void workerFailureReleasesPermitAndContinues() {
        events(event(1), event(2)); inline(); doThrow(new IllegalStateException()).when(worker).process(any());
        dispatcher.dispatchDueEvents();
        verify(worker, times(2)).process(any()); assertEquals(2, semaphore.availablePermits());
    }
    @Test void inlineExecutorThrowingAfterTaskDoesNotReleaseTwice() {
        events(event(1));
        doAnswer(i -> { ((Runnable) i.getArgument(0)).run(); throw new IllegalStateException(); }).when(executor).execute(any());
        dispatcher.dispatchDueEvents();
        assertEquals(2, semaphore.availablePermits()); verify(mapper, never()).releaseClaim(anyLong(), anyString());
    }
    @Test void rejectsExternalTransaction() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        try { assertThrows(IllegalStateException.class, dispatcher::dispatchDueEvents); }
        finally { TransactionSynchronizationManager.setActualTransactionActive(false); }
        verifyNoInteractions(mapper); assertEquals(2, semaphore.availablePermits());
    }
    @Test void rejectsNonAbortPolicyAndNonTransactionalTemplate() {
        when(executor.getRejectedExecutionHandler()).thenReturn(new ThreadPoolExecutor.DiscardPolicy());
        assertThrows(IllegalArgumentException.class, this::create);
        when(executor.getRejectedExecutionHandler()).thenReturn(new ThreadPoolExecutor.AbortPolicy());
        when(transaction.getPropagationBehavior()).thenReturn(TransactionDefinition.PROPAGATION_SUPPORTS);
        assertThrows(IllegalArgumentException.class, this::create);
    }

    @Test void logsReleaseMismatchAndSuppressesSensitiveExceptionDetails() {
        Logger logger = (Logger) LoggerFactory.getLogger(WebhookDispatcher.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            events(event(1), event(2));
            doThrow(new RejectedExecutionException("sensitive-submission"))
                    .when(executor).execute(any());
            when(mapper.releaseClaim(eq(1L), anyString())).thenReturn(0);
            when(mapper.releaseClaim(eq(2L), anyString()))
                    .thenThrow(new IllegalStateException("sensitive-database"));
            dispatcher.dispatchDueEvents();

            events(event(3));
            inline();
            doThrow(new IllegalArgumentException("sensitive-worker")).when(worker).process(any());
            dispatcher.dispatchDueEvents();

            assertTrue(appender.list.stream().anyMatch(e -> e.getLevel() == Level.WARN &&
                    e.getFormattedMessage().contains("eventId=event-1") &&
                    e.getFormattedMessage().contains("rows=0")));
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("eventId=event-2") &&
                    e.getFormattedMessage().contains("errorType=IllegalStateException")));
            assertTrue(appender.list.stream().anyMatch(e -> e.getFormattedMessage().contains("eventId=event-3") &&
                    e.getFormattedMessage().contains("errorType=IllegalArgumentException")));
            assertTrue(appender.list.stream().noneMatch(e -> e.getFormattedMessage().contains("sensitive-") ||
                    e.getThrowableProxy() != null));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test void realExecutorKeepsPermitsUntilWorkersFinish() throws Exception {
        ThreadPoolExecutor realExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            events(event(1), event(2));
            doAnswer(i -> { started.countDown(); assertTrue(finish.await(5, TimeUnit.SECONDS)); return null; })
                    .when(worker).process(any());
            WebhookDispatcher real = new WebhookDispatcher(mapper, transaction, realExecutor, semaphore,
                    clock, Duration.ofMinutes(2), worker);
            real.dispatchDueEvents();
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertEquals(0, semaphore.availablePermits());
            assertEquals(1, realExecutor.getQueue().size());
            finish.countDown();
            realExecutor.shutdown();
            assertTrue(realExecutor.awaitTermination(5, TimeUnit.SECONDS));
            assertEquals(2, semaphore.availablePermits());
            verify(worker, times(2)).process(any());
        } finally {
            finish.countDown();
            realExecutor.shutdownNow();
        }
    }

    @Test void saturatedRealExecutorReleasesRejectedClaim() throws Exception {
        ThreadPoolExecutor realExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try {
            realExecutor.execute(() -> {
                started.countDown();
                try { finish.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            realExecutor.execute(() -> {});
            events(event(1));
            when(mapper.releaseClaim(eq(1L), anyString())).thenReturn(1);
            WebhookDispatcher real = new WebhookDispatcher(mapper, transaction, realExecutor, semaphore,
                    clock, Duration.ofMinutes(2), worker);
            real.dispatchDueEvents();
            verify(mapper).releaseClaim(eq(1L), anyString());
            verifyNoInteractions(worker);
            assertEquals(2, semaphore.availablePermits());
        } finally {
            finish.countDown();
            realExecutor.shutdownNow();
            assertTrue(realExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
