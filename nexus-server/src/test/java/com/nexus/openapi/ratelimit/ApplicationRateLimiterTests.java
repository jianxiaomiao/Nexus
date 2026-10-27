package com.nexus.openapi.ratelimit;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ApplicationRateLimiterTests {

    @Test
    void waiterGaugeChangesFromZeroToOneAndBackToZero() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try {
            new ApplicationRateLimiterMetrics(limiter).bindTo(registry);

            var waiterGauge = registry
                    .get(ApplicationRateLimiterMetrics.WAITERS_METRIC)
                    .gauge();

            assertEquals(0.0, waiterGauge.value());

            assertInstanceOf(RateLimitAdmission.Allowed.class,
                    limiter.acquire(1L));
            assertEquals(0.0, waiterGauge.value());

            RateLimitAdmission.Queued queued =
                    assertInstanceOf(RateLimitAdmission.Queued.class,
                            limiter.acquire(1L));
            assertEquals(1.0, waiterGauge.value());

            manual.runNext();

            assertEquals(QueueDecision.ALLOWED,
                    queued.handle().completion().toCompletableFuture().join());
            assertEquals(0.0, waiterGauge.value());
        } finally {
            registry.close();
            limiter.close();
        }
    }

    @Test
    void stateGaugeGrowsWithApplicationsAndFallsAfterIdleCleanup() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();

        try {
            limiter.start();
            new ApplicationRateLimiterMetrics(limiter).bindTo(registry);

            var stateGauge = registry
                    .get(ApplicationRateLimiterMetrics.STATES_METRIC)
                    .gauge();

            assertEquals(0.0, stateGauge.value());

            assertInstanceOf(RateLimitAdmission.Allowed.class,
                    limiter.acquire(1L));
            assertEquals(1.0, stateGauge.value());

            assertInstanceOf(RateLimitAdmission.Allowed.class,
                    limiter.acquire(2L));
            assertEquals(2.0, stateGauge.value());

            manual.advanceBy(Duration.ofSeconds(10).toNanos());
            manual.runCleanup();

            assertEquals(0.0, stateGauge.value());
        } finally {
            registry.close();
            limiter.close();
        }
    }

    @Test
    void productionConfigurationAllowsOneThousandStatesAndRejectsTheNext() {
        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             ApplicationRateLimiterConfig.class)) {
            ApplicationRateLimiter limiter =
                    context.getBean(ApplicationRateLimiter.class);

            for (long applicationId = 1; applicationId <= 1_000; applicationId++) {
                assertInstanceOf(RateLimitAdmission.Allowed.class,
                        limiter.acquire(applicationId));
            }

            RateLimitAdmission.Rejected rejected =
                    assertInstanceOf(RateLimitAdmission.Rejected.class,
                            limiter.acquire(1_001L));

            assertAll(
                    () -> assertEquals(1_000, limiter.currentStateCount()),
                    () -> assertEquals(
                            RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                            rejected.reason()),
                    () -> assertEquals(0, limiter.currentWaiterCount()));
        }
    }

    @Test
    void concurrentDistinctApplicationsCannotExceedStateCapacity() throws Exception {
        int capacity = 1_000;
        int requestCount = capacity + 1;
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(
                manual, 100, 1_000, 10, 20, capacity);
        ExecutorService requests = Executors.newFixedThreadPool(32);
        CountDownLatch ready = new CountDownLatch(32);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<RateLimitAdmission>> futures = new ArrayList<>();
            for (long applicationId = 1; applicationId <= requestCount; applicationId++) {
                long currentApplicationId = applicationId;
                futures.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    return limiter.acquire(currentApplicationId);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<RateLimitAdmission> admissions = new ArrayList<>();
            for (Future<RateLimitAdmission> future : futures) {
                admissions.add(future.get(5, TimeUnit.SECONDS));
            }

            long allowed = admissions.stream()
                    .filter(RateLimitAdmission.Allowed.class::isInstance)
                    .count();
            List<RateLimitAdmission.Rejected> rejected = admissions.stream()
                    .filter(RateLimitAdmission.Rejected.class::isInstance)
                    .map(RateLimitAdmission.Rejected.class::cast)
                    .toList();

            assertAll(
                    () -> assertEquals(capacity, allowed),
                    () -> assertEquals(1, rejected.size()),
                    () -> assertEquals(
                            RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                            rejected.getFirst().reason()),
                    () -> assertEquals(capacity, limiter.currentStateCount()),
                    () -> assertEquals(0, limiter.currentWaiterCount()));
        } finally {
            limiter.close();
            requests.shutdownNow();
        }
    }

    @Test
    void cleanupStartsAfterFiveSecondsAndUsesFiveSecondFixedDelay() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);

        try {
            limiter.start();

            verify(manual.scheduler()).scheduleWithFixedDelay(
                    any(Runnable.class),
                    eq(Duration.ofSeconds(5).toNanos()),
                    eq(Duration.ofSeconds(5).toNanos()),
                    eq(NANOSECONDS));
        } finally {
            limiter.close();
        }
    }

    @Test
    void oneHundredAndFirstGlobalWaiterIsRejectedWhenItsApplicationHasQueueSpace() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 100);

        try {
            for (long applicationId = 1; applicationId <= 11; applicationId++) {
                assertInstanceOf(RateLimitAdmission.Allowed.class,
                        limiter.acquire(applicationId));
            }

            for (long applicationId = 1; applicationId <= 10; applicationId++) {
                for (int waiter = 0; waiter < 10; waiter++) {
                    assertInstanceOf(RateLimitAdmission.Queued.class,
                            limiter.acquire(applicationId));
                }
            }

            assertEquals(100, limiter.currentWaiterCount());
            assertEquals(11, limiter.currentStateCount());

            RateLimitAdmission.Rejected globalRejection =
                    assertInstanceOf(RateLimitAdmission.Rejected.class,
                            limiter.acquire(11L));
            assertEquals(RateLimitAdmission.RejectReason.GLOBAL_QUEUE_FULL,
                    globalRejection.reason());
            assertEquals(100, limiter.currentWaiterCount());

            RateLimitAdmission.Rejected applicationRejection =
                    assertInstanceOf(RateLimitAdmission.Rejected.class,
                            limiter.acquire(1L));
            assertEquals(RateLimitAdmission.RejectReason.APPLICATION_QUEUE_FULL,
                    applicationRejection.reason());
            assertEquals(100, limiter.currentWaiterCount());
        } finally {
            limiter.close();
        }
    }

    @Test
    void firstRequestIsAllowedAndQueuedRequestIsReleasedAtTheNextPermit() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);

        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(1L));
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        assertFalse(queued.handle().completion().toCompletableFuture().isDone());
        manual.runNext();

        assertEquals(QueueDecision.ALLOWED,
                queued.handle().completion().toCompletableFuture().join());
    }

    @Test
    void waiterTimesOutWhenItsDeadlineArrivesBeforeTheNextPermit() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 200, 100, 10, 20);

        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(1L));
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        manual.runNext();

        assertEquals(QueueDecision.TIMED_OUT,
                queued.handle().completion().toCompletableFuture().join());
    }

    @Test
    void waiterIsAllowedWhenPermitArrivesExactlyAtItsDeadline() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 100, 10, 20);

        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(1L));
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        manual.runNext();

        assertEquals(QueueDecision.ALLOWED,
                queued.handle().completion().toCompletableFuture().join());
    }

    @Test
    void cancellingAWaitingRequestCompletesItOnceAndRemovesItFromTheQueue() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);

        limiter.acquire(1L);
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        assertTrue(queued.handle().cancel());
        assertFalse(queued.handle().cancel());
        assertEquals(QueueDecision.CANCELLED,
                queued.handle().completion().toCompletableFuture().join());

        // 原来的唤醒任务仍可安全执行，并负责把 drainTask 复位。
        manual.runNext();
        assertFalse(manual.hasTasks());
    }

    @Test
    void closeFailsQueuedRequestsFromEveryApplicationAndRejectsNewAdmissions() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 20);

        limiter.acquire(1L);
        RateLimitAdmission.Queued first =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));
        limiter.acquire(2L);
        RateLimitAdmission.Queued second =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(2L));

        limiter.close();

        assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                first.handle().completion().toCompletableFuture().join());
        assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                second.handle().completion().toCompletableFuture().join());

        RateLimitAdmission.Rejected rejected =
                assertInstanceOf(RateLimitAdmission.Rejected.class, limiter.acquire(3L));
        assertEquals(RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE, rejected.reason());

        manual.scheduledFutures().forEach(
                future -> verify(future).cancel(false));
    }

    @Test
    void repeatedCloseDoesNotCancelATaskOrReleaseItsWaiterTwice() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 1);

        limiter.acquire(1L);
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));
        ScheduledFuture<?> scheduledFuture = manual.scheduledFutures().getFirst();

        limiter.close();
        limiter.close();

        assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                queued.handle().completion().toCompletableFuture().join());
        verify(scheduledFuture, times(1)).cancel(false);
    }

    @Test
    void rejectedInitialSchedulingRollsBackTheGlobalWaitingSlot() {
        ManualScheduler manual = new ManualScheduler();
        manual.rejectScheduleCall(1);
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 1);

        limiter.acquire(1L);
        RateLimitAdmission.Rejected rejected =
                assertInstanceOf(RateLimitAdmission.Rejected.class, limiter.acquire(1L));
        assertEquals(RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE, rejected.reason());

        // 如果上一次失败没有归还唯一的全局槽位，这里会得到 GLOBAL_QUEUE_FULL。
        limiter.acquire(2L);
        assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(2L));
        limiter.close();
    }

    @Test
    void rejectedReschedulingKeepsTheCurrentDecisionAndFailsRemainingWaiters() {
        ManualScheduler manual = new ManualScheduler();
        manual.rejectScheduleCall(2);
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 2);

        limiter.acquire(1L);
        RateLimitAdmission.Queued first =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));
        RateLimitAdmission.Queued second =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        manual.runNext();

        assertEquals(QueueDecision.ALLOWED,
                first.handle().completion().toCompletableFuture().join());
        assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                second.handle().completion().toCompletableFuture().join());
    }

    @Test
    void concurrentAdmissionsForOneApplicationAllowOneAndQueueTheRest() throws Exception {
        int requestCount = 20;
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter =
                limiter(manual, 100, 10_000, requestCount - 1, requestCount - 1);
        ExecutorService requests = Executors.newFixedThreadPool(requestCount);
        CountDownLatch ready = new CountDownLatch(requestCount);
        CountDownLatch start = new CountDownLatch(1);

        try {
            List<Future<RateLimitAdmission>> futures = new ArrayList<>();
            for (int index = 0; index < requestCount; index++) {
                futures.add(requests.submit(() -> {
                    ready.countDown();
                    assertTrue(start.await(2, TimeUnit.SECONDS));
                    return limiter.acquire(1L);
                }));
            }

            assertTrue(ready.await(2, TimeUnit.SECONDS));
            start.countDown();

            List<RateLimitAdmission> admissions = new ArrayList<>();
            for (Future<RateLimitAdmission> future : futures) {
                admissions.add(future.get(2, TimeUnit.SECONDS));
            }

            assertEquals(1, admissions.stream()
                    .filter(RateLimitAdmission.Allowed.class::isInstance).count());
            assertEquals(requestCount - 1L, admissions.stream()
                    .filter(RateLimitAdmission.Queued.class::isInstance).count());
            assertEquals(1, manual.taskCount());

            limiter.close();
            for (RateLimitAdmission admission : admissions) {
                if (admission instanceof RateLimitAdmission.Queued queued) {
                    assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                            queued.handle().completion().toCompletableFuture().join());
                }
            }
        } finally {
            requests.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void cancellationAndCloseRaceProducesExactlyOneTerminalDecision() throws Exception {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 1);
        limiter.acquire(1L);
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));
        ExecutorService racers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> cancellation = racers.submit(() -> {
                start.await();
                return queued.handle().cancel();
            });
            Future<?> closure = racers.submit(() -> {
                start.await();
                limiter.close();
                return null;
            });

            start.countDown();
            cancellation.get(2, TimeUnit.SECONDS);
            closure.get(2, TimeUnit.SECONDS);

            QueueDecision decision =
                    queued.handle().completion().toCompletableFuture().join();
            assertTrue(decision == QueueDecision.CANCELLED
                    || decision == QueueDecision.LIMITER_UNAVAILABLE);
            assertFalse(queued.handle().cancel());
        } finally {
            racers.shutdownNow();
        }
    }

    @RepeatedTest(20)
    void allowanceAndCancellationRaceProducesOneDecisionAndReleasesCapacity()
            throws Exception {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 1);
        limiter.acquire(1L);
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));
        ExecutorService racers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);

        try {
            Future<Boolean> cancellation = racers.submit(() -> {
                start.await();
                return queued.handle().cancel();
            });
            Future<?> allowance = racers.submit(() -> {
                start.await();
                manual.runNext();
                return null;
            });

            start.countDown();
            boolean cancelled = cancellation.get(2, TimeUnit.SECONDS);
            allowance.get(2, TimeUnit.SECONDS);

            QueueDecision decision =
                    queued.handle().completion().toCompletableFuture().join();
            assertTrue(decision == QueueDecision.ALLOWED
                    || decision == QueueDecision.CANCELLED);
            assertEquals(decision == QueueDecision.CANCELLED, cancelled);
            assertFalse(queued.handle().cancel());

            // 唯一的全局等待槽位必须只释放一次，并且能够被再次占用。
            limiter.acquire(2L);
            assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(2L));
        } finally {
            limiter.close();
            racers.shutdownNow();
        }
    }

    @Test
    void invalidApplicationIdUsesTheRateLimiterSpecificException() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(manual, 100, 1_000, 10, 10);

        assertAll(
                () -> assertThrows(InvalidRateLimitApplicationIdException.class,
                        () -> limiter.acquire(null)),
                () -> assertThrows(InvalidRateLimitApplicationIdException.class,
                        () -> limiter.acquire(0L)),
                () -> assertThrows(InvalidRateLimitApplicationIdException.class,
                        () -> limiter.acquire(-1L)));
    }

    @Test
    void idleStateIsKeptBeforeTtlAndReclaimedAtTtl() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = limiter(
                manual, 100, 1_000, 10, 20, 1);
        limiter.start();

        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(1L));

        manual.advanceBy(Duration.ofSeconds(10).toNanos() - 1L);
        manual.runCleanup();
        RateLimitAdmission.Rejected beforeTtl =
                assertInstanceOf(RateLimitAdmission.Rejected.class, limiter.acquire(2L));
        assertEquals(RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                beforeTtl.reason());

        manual.advanceBy(1L);
        manual.runCleanup();
        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(2L));

        limiter.close();
        verify(manual.cleanupFuture(), times(1)).cancel(false);
    }

    @Test
    void stateWithAWaiterIsNotReclaimedAndCloseCompletesTheWaiter() {
        ManualScheduler manual = new ManualScheduler();
        ApplicationRateLimiter limiter = new ApplicationRateLimiter(
                manual.scheduler(),
                10,
                20,
                1,
                Duration.ofSeconds(30),
                Duration.ofSeconds(20),
                manual::now);
        limiter.start();

        assertInstanceOf(RateLimitAdmission.Allowed.class, limiter.acquire(1L));
        RateLimitAdmission.Queued queued =
                assertInstanceOf(RateLimitAdmission.Queued.class, limiter.acquire(1L));

        manual.advanceBy(Duration.ofSeconds(10).toNanos());
        manual.runCleanup();
        RateLimitAdmission.Rejected otherApplication =
                assertInstanceOf(RateLimitAdmission.Rejected.class, limiter.acquire(2L));
        assertEquals(RateLimitAdmission.RejectReason.STATE_CAPACITY_REACHED,
                otherApplication.reason());

        limiter.close();
        assertEquals(QueueDecision.LIMITER_UNAVAILABLE,
                queued.handle().completion().toCompletableFuture().join());
    }

    @Test
    void springContextStartsLimiterAndClosesLimiterBeforeItsScheduler() {
        ApplicationRateLimiter limiter;
        ScheduledExecutorService scheduler;

        try (AnnotationConfigApplicationContext context =
                     new AnnotationConfigApplicationContext(
                             ApplicationRateLimiterConfig.class)) {
            limiter = context.getBean(ApplicationRateLimiter.class);
            scheduler = context.getBean(
                    "applicationRateScheduler",
                    ScheduledExecutorService.class);

            assertFalse(scheduler.isShutdown());
            assertInstanceOf(RateLimitAdmission.Allowed.class,
                    limiter.acquire(1L));
        }

        assertTrue(scheduler.isShutdown());
        RateLimitAdmission.Rejected afterClose =
                assertInstanceOf(RateLimitAdmission.Rejected.class,
                        limiter.acquire(2L));
        assertEquals(RateLimitAdmission.RejectReason.LIMITER_UNAVAILABLE,
                afterClose.reason());
    }

    private static ApplicationRateLimiter limiter(
            ManualScheduler manual,
            long intervalNanos,
            long waitTimeoutNanos,
            int maxApplicationWaiters,
            int maxGlobalWaiters) {

        return new ApplicationRateLimiter(
                manual.scheduler(),
                maxApplicationWaiters,
                maxGlobalWaiters,
                100,
                Duration.ofNanos(intervalNanos),
                Duration.ofNanos(waitTimeoutNanos),
                manual::now);
    }

    private static ApplicationRateLimiter limiter(
            ManualScheduler manual,
            long intervalNanos,
            long waitTimeoutNanos,
            int maxApplicationWaiters,
            int maxGlobalWaiters,
            int maxApplicationStates) {

        return new ApplicationRateLimiter(
                manual.scheduler(),
                maxApplicationWaiters,
                maxGlobalWaiters,
                maxApplicationStates,
                Duration.ofNanos(intervalNanos),
                Duration.ofNanos(waitTimeoutNanos),
                manual::now);
    }

    private static final class ManualScheduler {
        private final ScheduledExecutorService scheduler =
                mock(ScheduledExecutorService.class);
        private final AtomicLong now = new AtomicLong(1_000_000L);
        private final AtomicInteger scheduleCalls = new AtomicInteger();
        private final Set<Integer> rejectedCalls = ConcurrentHashMap.newKeySet();
        private final ConcurrentLinkedQueue<ScheduledCall> tasks =
                new ConcurrentLinkedQueue<>();
        private final CopyOnWriteArrayList<ScheduledFuture<?>> futures =
                new CopyOnWriteArrayList<>();
        private final ScheduledFuture<?> cleanupFuture = mock(ScheduledFuture.class);
        private volatile Runnable cleanupCommand;

        private ManualScheduler() {
            when(scheduler.schedule(
                    any(Runnable.class),
                    anyLong(),
                    eq(NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        int call = scheduleCalls.incrementAndGet();
                        if (rejectedCalls.contains(call)) {
                            throw new RejectedExecutionException("test rejection " + call);
                        }

                        Runnable command = invocation.getArgument(0);
                        long delayNanos = invocation.getArgument(1);
                        ScheduledFuture<?> future = mock(ScheduledFuture.class);
                        futures.add(future);
                        tasks.add(new ScheduledCall(command, delayNanos, future));
                        return future;
                    });

            when(scheduler.scheduleWithFixedDelay(
                    any(Runnable.class),
                    anyLong(),
                    anyLong(),
                    eq(NANOSECONDS)))
                    .thenAnswer(invocation -> {
                        cleanupCommand = invocation.getArgument(0);
                        return cleanupFuture;
                    });
        }

        private ScheduledExecutorService scheduler() {
            return scheduler;
        }

        private long now() {
            return now.get();
        }

        private void rejectScheduleCall(int call) {
            rejectedCalls.add(call);
        }

        private void runNext() {
            ScheduledCall task = tasks.poll();
            assertNotNull(task, "expected a scheduled drain task");
            now.addAndGet(task.delayNanos());
            task.command().run();
        }

        private void advanceBy(long nanos) {
            now.addAndGet(nanos);
        }

        private void runCleanup() {
            assertNotNull(cleanupCommand, "expected a scheduled cleanup task");
            cleanupCommand.run();
        }

        private ScheduledFuture<?> cleanupFuture() {
            return cleanupFuture;
        }

        private boolean hasTasks() {
            return !tasks.isEmpty();
        }

        private int taskCount() {
            return tasks.size();
        }

        private List<ScheduledFuture<?>> scheduledFutures() {
            return List.copyOf(futures);
        }
    }

    private record ScheduledCall(
            Runnable command,
            long delayNanos,
            ScheduledFuture<?> future) {
    }
}
