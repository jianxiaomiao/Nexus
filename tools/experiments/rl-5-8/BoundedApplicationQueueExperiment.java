import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Experiments 12-15 / RL-5..RL-8: bounded waiting, deadline, cancellation and
 * concurrent admission for an Application-scoped smooth-rate queue.
 *
 * Time is virtual. No thread sleeps while holding limiter state.
 */
public class BoundedApplicationQueueExperiment {
    private static final Duration PERMIT_INTERVAL = Duration.ofMillis(100);
    private static final Duration MAX_WAIT = Duration.ofSeconds(1);
    private static final int MAX_WAITING = 10;
    private static final long APP_A = 9001;
    private static final long APP_B = 9002;

    public static void main(String[] args) throws Exception {
        queueCapacityRejectsWithoutLeakingSlots();
        deadlineBoundaryAndLateWakeReleaseCapacity();
        cancellationReleasesOnlyWaitingCapacity();
        concurrentAdmissionPreservesCountsAndFifo();
        System.out.println("RL-5..RL-8 PASS: capacity, timeout, cancellation and concurrency assertions passed.");
    }

    private static void queueCapacityRejectsWithoutLeakingSlots() {
        ApplicationQueueLimiter limiter = newLimiter();
        List<Admission> admissions = new ArrayList<>();

        for (int request = 1; request <= 15; request++) {
            admissions.add(limiter.submit(APP_A, "capacity-" + request, ms(0)));
        }

        assertCount(admissions, AdmissionStatus.ALLOWED_IMMEDIATELY, 1, "RL-5 immediate");
        assertCount(admissions, AdmissionStatus.QUEUED, 10, "RL-5 queued");
        assertCount(admissions, AdmissionStatus.REJECTED_QUEUE_FULL, 4, "RL-5 queue-full");
        assertEquals(10, limiter.waitingCount(APP_A), "RL-5 waiting count");

        System.out.println("RL-5 - bounded queue:");
        System.out.println("15 arrivals -> immediate=1 queued=10 rejectedQueueFull=4 waitingCount=10");
    }

    private static void deadlineBoundaryAndLateWakeReleaseCapacity() {
        ApplicationQueueLimiter exactBoundaryLimiter = newLimiter();
        exactBoundaryLimiter.submit(APP_A, "boundary-immediate", ms(0));
        for (int request = 1; request <= 10; request++) {
            Admission admission = exactBoundaryLimiter.submit(
                    APP_A, "boundary-waiter-" + request, ms(0));
            assertStatus(AdmissionStatus.QUEUED, admission, "RL-6 boundary enqueue");
        }

        for (int slot = 1; slot <= 10; slot++) {
            ReleaseOutcome outcome = exactBoundaryLimiter.releaseNext(APP_A, ms(slot * 100L));
            assertEquals(0, outcome.timedOutRequestIds().size(),
                    "RL-6 unexpected timeout at slot " + slot);
            assertEquals("boundary-waiter-" + slot,
                    outcome.permittedRequestId().orElseThrow(),
                    "RL-6 FIFO request at slot " + slot);
        }
        assertEquals(0, exactBoundaryLimiter.waitingCount(APP_A),
                "RL-6 boundary waiting count");

        ApplicationQueueLimiter lateWakeLimiter = newLimiter();
        lateWakeLimiter.submit(APP_A, "late-immediate", ms(0));
        for (int request = 1; request <= 10; request++) {
            lateWakeLimiter.submit(APP_A, "late-waiter-" + request, ms(0));
        }
        ReleaseOutcome lateOutcome = lateWakeLimiter.releaseNext(APP_A, ms(1_001));
        assertEquals(10, lateOutcome.timedOutRequestIds().size(), "RL-6 timed-out count");
        assertTrue(lateOutcome.permittedRequestId().isEmpty(),
                "RL-6 must not permit expired waiters");
        assertEquals(0, lateWakeLimiter.waitingCount(APP_A),
                "RL-6 timeout released waiting capacity");

        Admission replacement = lateWakeLimiter.submit(APP_A, "after-timeout", ms(1_001));
        assertStatus(AdmissionStatus.ALLOWED_IMMEDIATELY, replacement,
                "RL-6 request after timeout");

        System.out.println("RL-6 - deadline boundary:");
        System.out.println("permit at exactly 1000ms -> allowed; wake at 1001ms -> 10 timed out, capacity=0");
    }

    private static void cancellationReleasesOnlyWaitingCapacity() {
        ApplicationQueueLimiter limiter = newLimiter();
        limiter.submit(APP_A, "cancel-immediate", ms(0));
        limiter.submit(APP_A, "waiter-A", ms(0));
        limiter.submit(APP_A, "waiter-B", ms(0));
        limiter.submit(APP_A, "waiter-C", ms(0));
        assertEquals(3, limiter.waitingCount(APP_A), "RL-7 initial waiting count");

        assertTrue(limiter.cancel(APP_A, "waiter-B"), "RL-7 cancel waiting B");
        assertEquals(2, limiter.waitingCount(APP_A), "RL-7 count after cancellation");

        Admission replacement = limiter.submit(APP_A, "waiter-D", ms(0));
        assertStatus(AdmissionStatus.QUEUED, replacement, "RL-7 replacement admission");
        assertEquals(3, limiter.waitingCount(APP_A), "RL-7 count after replacement");

        ReleaseOutcome firstRelease = limiter.releaseNext(APP_A, ms(100));
        assertEquals("waiter-A", firstRelease.permittedRequestId().orElseThrow(),
                "RL-7 first release");
        assertEquals(2, limiter.waitingCount(APP_A), "RL-7 count after permit");
        assertTrue(!limiter.cancel(APP_A, "waiter-A"),
                "RL-7 already permitted request must not be canceled from queue");

        assertTrue(limiter.cancel(APP_A, "waiter-C"), "RL-7 cancel waiting C");
        assertEquals(1, limiter.waitingCount(APP_A), "RL-7 count after second cancellation");
        ReleaseOutcome secondRelease = limiter.releaseNext(APP_A, ms(200));
        assertEquals("waiter-D", secondRelease.permittedRequestId().orElseThrow(),
                "RL-7 compacted FIFO release");
        assertEquals(0, limiter.waitingCount(APP_A), "RL-7 final waiting count");

        System.out.println("RL-7 - cancellation:");
        System.out.println("cancel waiting B -> capacity freed; A permitted; cancel waiting C; D moved forward to 200ms");
    }

    private static void concurrentAdmissionPreservesCountsAndFifo() throws Exception {
        ApplicationQueueLimiter limiter = newLimiter();
        ExecutorService executor = Executors.newFixedThreadPool(15);
        CountDownLatch startGate = new CountDownLatch(1);
        List<Future<Admission>> futures = new ArrayList<>();

        try {
            for (int request = 1; request <= 15; request++) {
                String requestId = "concurrent-" + request;
                futures.add(executor.submit(() -> {
                    startGate.await();
                    return limiter.submit(APP_A, requestId, ms(0));
                }));
            }
            startGate.countDown();

            List<Admission> admissions = new ArrayList<>();
            for (Future<Admission> future : futures) {
                admissions.add(future.get(5, TimeUnit.SECONDS));
            }

            assertCount(admissions, AdmissionStatus.ALLOWED_IMMEDIATELY, 1,
                    "RL-8 immediate");
            assertCount(admissions, AdmissionStatus.QUEUED, 10, "RL-8 queued");
            assertCount(admissions, AdmissionStatus.REJECTED_QUEUE_FULL, 4,
                    "RL-8 queue-full");
            assertEquals(10, limiter.waitingCount(APP_A), "RL-8 waiting count");

            List<String> expectedFifo = admissions.stream()
                    .filter(admission -> admission.status() == AdmissionStatus.QUEUED)
                    .sorted(Comparator.comparingLong(Admission::queueSequence))
                    .map(Admission::requestId)
                    .toList();
            List<String> actualReleaseOrder = new ArrayList<>();
            for (int slot = 1; slot <= 10; slot++) {
                ReleaseOutcome outcome = limiter.releaseNext(APP_A, ms(slot * 100L));
                actualReleaseOrder.add(outcome.permittedRequestId().orElseThrow());
            }
            assertEquals(expectedFifo, actualReleaseOrder, "RL-8 FIFO release order");
            assertEquals(0, limiter.waitingCount(APP_A), "RL-8 final waiting count");

            Admission independentApplication = limiter.submit(APP_B, "application-B", ms(0));
            assertStatus(AdmissionStatus.ALLOWED_IMMEDIATELY, independentApplication,
                    "RL-8 independent Application");

            System.out.println("RL-8 - concurrent admission and FIFO:");
            System.out.println("15 threads -> immediate=1 queued=10 rejectedQueueFull=4; FIFO preserved");
            System.out.println("Application B at the same virtual time -> immediate, independent bucket");
        } finally {
            executor.shutdownNow();
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new AssertionError("RL-8 executor did not terminate");
            }
        }
    }

    private static ApplicationQueueLimiter newLimiter() {
        return new ApplicationQueueLimiter(PERMIT_INTERVAL, MAX_WAIT, MAX_WAITING);
    }

    private static void assertCount(
            List<Admission> admissions,
            AdmissionStatus status,
            long expected,
            String description) {
        long actual = admissions.stream().filter(admission -> admission.status() == status).count();
        assertEquals(expected, actual, description);
    }

    private static void assertStatus(
            AdmissionStatus expected, Admission actual, String description) {
        if (actual.status() != expected) {
            throw new AssertionError(description + ": expected=" + expected
                    + ", actual=" + actual.status());
        }
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertEquals(Object expected, Object actual, String description) {
        if (!expected.equals(actual)) {
            throw new AssertionError(description + ": expected=" + expected
                    + ", actual=" + actual);
        }
    }

    private static void assertTrue(boolean condition, String description) {
        if (!condition) {
            throw new AssertionError(description);
        }
    }

    private static long ms(long milliseconds) {
        return Duration.ofMillis(milliseconds).toNanos();
    }

    private enum AdmissionStatus {
        ALLOWED_IMMEDIATELY,
        QUEUED,
        REJECTED_QUEUE_FULL
    }

    private record Admission(
            String requestId,
            AdmissionStatus status,
            long queueSequence,
            long projectedDelayNanos) {
    }

    private record ReleaseOutcome(
            List<String> timedOutRequestIds,
            Optional<String> permittedRequestId) {
    }

    private record Waiter(
            String requestId,
            long sequence,
            long deadlineNanos) {
    }

    private static final class ApplicationQueueLimiter {
        private final long intervalNanos;
        private final long maxWaitNanos;
        private final int maxWaiting;
        private final Map<Long, Bucket> buckets = new HashMap<>();

        private ApplicationQueueLimiter(
                Duration interval, Duration maxWait, int maxWaiting) {
            this.intervalNanos = interval.toNanos();
            this.maxWaitNanos = maxWait.toNanos();
            this.maxWaiting = maxWaiting;
            if (Math.multiplyExact(this.intervalNanos, (long) maxWaiting)
                    > this.maxWaitNanos) {
                throw new IllegalArgumentException(
                        "queue capacity and interval can project a wait beyond maxWait");
            }
        }

        synchronized Admission submit(long applicationId, String requestId, long nowNanos) {
            Bucket bucket = buckets.computeIfAbsent(applicationId, ignored -> new Bucket());
            removeExpired(bucket, nowNanos);

            if (bucket.waiters.isEmpty()
                    && (bucket.nextPermitAtNanos == null
                    || nowNanos >= bucket.nextPermitAtNanos)) {
                bucket.nextPermitAtNanos = Math.addExact(nowNanos, intervalNanos);
                return new Admission(requestId, AdmissionStatus.ALLOWED_IMMEDIATELY, -1, 0);
            }

            if (bucket.waiters.size() >= maxWaiting) {
                return new Admission(requestId, AdmissionStatus.REJECTED_QUEUE_FULL, -1, 0);
            }

            long firstFutureSlot = Math.max(nowNanos, bucket.nextPermitAtNanos);
            long projectedAt = Math.addExact(
                    firstFutureSlot,
                    Math.multiplyExact((long) bucket.waiters.size(), intervalNanos));
            long projectedDelay = projectedAt - nowNanos;

            long sequence = bucket.nextSequence++;
            bucket.waiters.add(new Waiter(
                    requestId, sequence, Math.addExact(nowNanos, maxWaitNanos)));
            return new Admission(requestId, AdmissionStatus.QUEUED, sequence, projectedDelay);
        }

        synchronized ReleaseOutcome releaseNext(long applicationId, long nowNanos) {
            Bucket bucket = buckets.get(applicationId);
            if (bucket == null) {
                return new ReleaseOutcome(List.of(), Optional.empty());
            }

            List<String> timedOut = removeExpired(bucket, nowNanos);
            if (bucket.waiters.isEmpty()
                    || (bucket.nextPermitAtNanos != null
                    && nowNanos < bucket.nextPermitAtNanos)) {
                return new ReleaseOutcome(timedOut, Optional.empty());
            }

            Waiter permitted = bucket.waiters.remove();
            bucket.nextPermitAtNanos = Math.addExact(nowNanos, intervalNanos);
            return new ReleaseOutcome(timedOut, Optional.of(permitted.requestId()));
        }

        synchronized boolean cancel(long applicationId, String requestId) {
            Bucket bucket = buckets.get(applicationId);
            if (bucket == null) {
                return false;
            }
            return bucket.waiters.removeIf(waiter -> waiter.requestId().equals(requestId));
        }

        synchronized int waitingCount(long applicationId) {
            Bucket bucket = buckets.get(applicationId);
            return bucket == null ? 0 : bucket.waiters.size();
        }

        private List<String> removeExpired(Bucket bucket, long nowNanos) {
            List<String> timedOut = new ArrayList<>();
            bucket.waiters.removeIf(waiter -> {
                // Equality is allowed: "at most one second" includes exactly 1000ms.
                boolean expired = nowNanos > waiter.deadlineNanos();
                if (expired) {
                    timedOut.add(waiter.requestId());
                }
                return expired;
            });
            return List.copyOf(timedOut);
        }

        private static final class Bucket {
            private final Queue<Waiter> waiters = new ArrayDeque<>();
            private Long nextPermitAtNanos;
            private long nextSequence;
        }
    }
}
