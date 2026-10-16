import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;

/** Experiment 17 / RESOURCE-1: bound global waiters and retained Application states. */
public class GlobalResourceBoundExperiment {
    private static final long INTERVAL = Duration.ofMillis(100).toNanos();
    private static final long DEADLINE = Duration.ofSeconds(1).toNanos();

    public static void main(String[] args) {
        globalWaitingCapacityIsSharedAcrossApplications();
        stateCapacityRejectsThenReusesIdleState();
        System.out.println("RESOURCE-1 PASS: global waiters and Application states stayed bounded.");
    }

    private static void globalWaitingCapacityIsSharedAcrossApplications() {
        ResourceBoundLimiter limiter = new ResourceBoundLimiter(10, 12, 10);
        assertStatus(Status.IMMEDIATE, limiter.submit(9001, "A-0", 0), "A first");
        assertStatus(Status.IMMEDIATE, limiter.submit(9002, "B-0", 0), "B first");

        for (int index = 1; index <= 6; index++) {
            assertStatus(Status.QUEUED, limiter.submit(9001, "A-" + index, 0), "A queue");
            assertStatus(Status.QUEUED, limiter.submit(9002, "B-" + index, 0), "B queue");
        }
        assertEquals(12, limiter.globalWaiting(), "global waiters at capacity");
        assertStatus(Status.REJECTED_GLOBAL_WAITERS,
                limiter.submit(9001, "A-overflow", 0), "global waiter rejection");
        assertEquals(12, limiter.globalWaiting(), "rejection did not change global count");

        assertTrue(limiter.cancel(9001, "A-3"), "cancel A-3");
        assertEquals(11, limiter.globalWaiting(), "cancel released global capacity");
        assertStatus(Status.QUEUED, limiter.submit(9002, "B-replacement", 0),
                "replacement after cancel");
        assertEquals(12, limiter.globalWaiting(), "replacement consumed released capacity");

        int timedOut = limiter.expire(ms(1_001));
        assertEquals(12, timedOut, "expired global waiters");
        assertEquals(0, limiter.globalWaiting(), "timeouts released global capacity");

        System.out.println("global waiters: A=6 + B=6 -> 12; overflow rejected; cancel -1; replacement +1; timeout -> 0");
    }

    private static void stateCapacityRejectsThenReusesIdleState() {
        ResourceBoundLimiter limiter = new ResourceBoundLimiter(10, 20, 3);
        assertStatus(Status.IMMEDIATE, limiter.submit(9101, "state-A", 0), "state A");
        assertStatus(Status.IMMEDIATE, limiter.submit(9102, "state-B", 0), "state B");
        assertStatus(Status.IMMEDIATE, limiter.submit(9103, "state-C", 0), "state C");
        assertEquals(3, limiter.stateCount(), "state count at capacity");

        assertStatus(Status.REJECTED_STATE_CAPACITY,
                limiter.submit(9104, "state-D-too-early", 0), "active state capacity");
        assertEquals(3, limiter.stateCount(), "state rejection did not allocate state");

        Admission afterCooldown = limiter.submit(9104, "state-D-after-cooldown", INTERVAL);
        assertStatus(Status.IMMEDIATE, afterCooldown, "idle state reuse");
        assertEquals(3, limiter.stateCount(), "idle eviction kept state count bounded");
        assertTrue(limiter.hasState(9104), "new Application state exists");

        System.out.println("state cap=3: fourth active state rejected; after 100ms one idle state evicted and D admitted; count=3");
    }

    private static void assertStatus(Status expected, Admission actual, String description) {
        if (actual.status() != expected) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual.status());
        }
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
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

    private enum Status {
        IMMEDIATE,
        QUEUED,
        REJECTED_APPLICATION_WAITERS,
        REJECTED_GLOBAL_WAITERS,
        REJECTED_STATE_CAPACITY
    }

    private record Admission(Status status) {
    }

    private record Waiter(String requestId, long deadlineNanos) {
    }

    private static final class ResourceBoundLimiter {
        private final int maxApplicationWaiters;
        private final int maxGlobalWaiters;
        private final int maxStates;
        private final Map<Long, Bucket> buckets = new HashMap<>();
        private int globalWaiting;
        private long touchSequence;

        private ResourceBoundLimiter(
                int maxApplicationWaiters, int maxGlobalWaiters, int maxStates) {
            this.maxApplicationWaiters = maxApplicationWaiters;
            this.maxGlobalWaiters = maxGlobalWaiters;
            this.maxStates = maxStates;
        }

        synchronized Admission submit(long applicationId, String requestId, long nowNanos) {
            expire(nowNanos);
            Bucket bucket = buckets.get(applicationId);
            if (bucket == null) {
                if (buckets.size() >= maxStates && !evictOneIdle(nowNanos)) {
                    return new Admission(Status.REJECTED_STATE_CAPACITY);
                }
                bucket = new Bucket();
                buckets.put(applicationId, bucket);
            }
            bucket.lastTouched = touchSequence++;

            if (bucket.waiters.isEmpty()
                    && (bucket.nextPermitAt == null || nowNanos >= bucket.nextPermitAt)) {
                bucket.nextPermitAt = Math.addExact(nowNanos, INTERVAL);
                return new Admission(Status.IMMEDIATE);
            }
            if (bucket.waiters.size() >= maxApplicationWaiters) {
                return new Admission(Status.REJECTED_APPLICATION_WAITERS);
            }
            if (globalWaiting >= maxGlobalWaiters) {
                return new Admission(Status.REJECTED_GLOBAL_WAITERS);
            }
            bucket.waiters.add(new Waiter(requestId, Math.addExact(nowNanos, DEADLINE)));
            globalWaiting++;
            return new Admission(Status.QUEUED);
        }

        synchronized boolean cancel(long applicationId, String requestId) {
            Bucket bucket = buckets.get(applicationId);
            if (bucket == null) {
                return false;
            }
            boolean removed = bucket.waiters.removeIf(waiter -> waiter.requestId().equals(requestId));
            if (removed) {
                globalWaiting--;
                bucket.lastTouched = touchSequence++;
            }
            return removed;
        }

        synchronized int expire(long nowNanos) {
            int before = globalWaiting;
            for (Bucket bucket : buckets.values()) {
                int bucketBefore = bucket.waiters.size();
                bucket.waiters.removeIf(waiter -> nowNanos > waiter.deadlineNanos());
                globalWaiting -= bucketBefore - bucket.waiters.size();
            }
            return before - globalWaiting;
        }

        synchronized int globalWaiting() {
            return globalWaiting;
        }

        synchronized int stateCount() {
            return buckets.size();
        }

        synchronized boolean hasState(long applicationId) {
            return buckets.containsKey(applicationId);
        }

        private boolean evictOneIdle(long nowNanos) {
            Long candidateId = null;
            long candidateTouch = Long.MAX_VALUE;
            Iterator<Map.Entry<Long, Bucket>> iterator = buckets.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Bucket> entry = iterator.next();
                Bucket bucket = entry.getValue();
                boolean idle = bucket.waiters.isEmpty()
                        && bucket.nextPermitAt != null
                        && nowNanos >= bucket.nextPermitAt;
                if (idle && bucket.lastTouched < candidateTouch) {
                    candidateId = entry.getKey();
                    candidateTouch = bucket.lastTouched;
                }
            }
            if (candidateId == null) {
                return false;
            }
            buckets.remove(candidateId);
            return true;
        }

        private static final class Bucket {
            private final Queue<Waiter> waiters = new ArrayDeque<>();
            private Long nextPermitAt;
            private long lastTouched;
        }
    }
}
