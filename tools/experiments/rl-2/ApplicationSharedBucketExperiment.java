import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.ToLongFunction;

/**
 * Experiment 9 / RL-2: compare API-Key buckets with authenticated Application buckets.
 *
 * This is a deterministic identity experiment. It has no credentials, HTTP, threads,
 * queue capacity, timeout, Spring or database behavior.
 */
public class ApplicationSharedBucketExperiment {
    private static final Duration PERMIT_INTERVAL = Duration.ofMillis(100);
    private static final long ARRIVAL = ms(10_000);

    public static void main(String[] args) {
        ApiKeyIdentity keyA = new ApiKeyIdentity(101, 9001);
        ApiKeyIdentity keyB = new ApiKeyIdentity(102, 9001);

        demonstrateWhyPerKeyBucketsAllowBypass(keyA, keyB);
        verifyApplicationBucketsAreShared(keyA, keyB);
        System.out.println("RL-2 PASS: two Keys of one authenticated Application shared one schedule.");
    }

    private static void demonstrateWhyPerKeyBucketsAllowBypass(
            ApiKeyIdentity keyA, ApiKeyIdentity keyB) {
        BucketedSchedules wrongPerKeyDesign = new BucketedSchedules(ApiKeyIdentity::apiKeyId);

        Reservation first = wrongPerKeyDesign.reserve(keyA, ARRIVAL);
        Reservation afterKeySwitch = wrongPerKeyDesign.reserve(keyB, ARRIVAL);

        assertEquals(0, toMs(first.delayNanos()), "per-Key first request delay");
        assertEquals(0, toMs(afterKeySwitch.delayNanos()), "per-Key switched request delay");
        assertEquals(2, wrongPerKeyDesign.bucketCount(), "per-Key bucket count");

        System.out.println("Scenario A - wrong bucket key: apiKeyId");
        print("Key A", keyA, first);
        print("Key B", keyB, afterKeySwitch);
        System.out.println("bucketCount=" + wrongPerKeyDesign.bucketCount()
                + " -> switching Keys obtained another immediate slot");
    }

    private static void verifyApplicationBucketsAreShared(
            ApiKeyIdentity keyA, ApiKeyIdentity keyB) {
        BucketedSchedules perApplicationDesign =
                new BucketedSchedules(ApiKeyIdentity::applicationId);

        Reservation first = perApplicationDesign.reserve(keyA, ARRIVAL);
        Reservation afterKeySwitch = perApplicationDesign.reserve(keyB, ARRIVAL);
        Reservation third = perApplicationDesign.reserve(keyA, ARRIVAL);

        assertEquals(0, toMs(first.delayNanos()), "Application first request delay");
        assertEquals(100, toMs(afterKeySwitch.delayNanos()),
                "Application switched-Key request delay");
        assertEquals(200, toMs(third.delayNanos()), "Application third request delay");
        assertEquals(1, perApplicationDesign.bucketCount(), "Application bucket count");
        assertEquals(9001, first.bucketId(), "first Application bucket ID");
        assertEquals(9001, afterKeySwitch.bucketId(), "second Application bucket ID");
        assertEquals(9001, third.bucketId(), "third Application bucket ID");

        System.out.println("Scenario B - chosen bucket key: authenticated applicationId");
        print("Key A", keyA, first);
        print("Key B", keyB, afterKeySwitch);
        print("Key A", keyA, third);
        System.out.println("bucketCount=" + perApplicationDesign.bucketCount()
                + " -> both Keys advanced the same next-permit time");
    }

    private static void print(String label, ApiKeyIdentity identity, Reservation reservation) {
        System.out.printf(
                "%s apiKeyId=%d applicationId=%d bucketId=%d scheduled=%dms delay=%dms%n",
                label,
                identity.apiKeyId(),
                identity.applicationId(),
                reservation.bucketId(),
                toMs(reservation.scheduledAtNanos()),
                toMs(reservation.delayNanos()));
    }

    private static long ms(long milliseconds) {
        return Duration.ofMillis(milliseconds).toNanos();
    }

    private static long toMs(long nanoseconds) {
        return Duration.ofNanos(nanoseconds).toMillis();
    }

    private static void assertEquals(long expected, long actual, String description) {
        if (expected != actual) {
            throw new AssertionError(description + ": expected=" + expected + ", actual=" + actual);
        }
    }

    /** Mirrors the two trusted IDs already produced by Nexus API-Key authentication. */
    private record ApiKeyIdentity(long apiKeyId, long applicationId) {
    }

    private record Reservation(long bucketId, long arrivedAtNanos, long scheduledAtNanos) {
        long delayNanos() {
            return scheduledAtNanos - arrivedAtNanos;
        }
    }

    private static final class BucketedSchedules {
        private final ToLongFunction<ApiKeyIdentity> bucketSelector;
        private final Map<Long, SmoothSchedule> schedules = new HashMap<>();

        private BucketedSchedules(ToLongFunction<ApiKeyIdentity> bucketSelector) {
            this.bucketSelector = bucketSelector;
        }

        Reservation reserve(ApiKeyIdentity identity, long nowNanos) {
            long bucketId = bucketSelector.applyAsLong(identity);
            SmoothSchedule schedule = schedules.computeIfAbsent(
                    bucketId, ignored -> new SmoothSchedule(PERMIT_INTERVAL));
            long scheduledAtNanos = schedule.reserve(nowNanos);
            return new Reservation(bucketId, nowNanos, scheduledAtNanos);
        }

        int bucketCount() {
            return schedules.size();
        }
    }

    private static final class SmoothSchedule {
        private final long intervalNanos;
        private Long nextPermitAtNanos;

        private SmoothSchedule(Duration interval) {
            this.intervalNanos = interval.toNanos();
        }

        long reserve(long nowNanos) {
            long scheduledAtNanos = nextPermitAtNanos == null || nowNanos >= nextPermitAtNanos
                    ? nowNanos
                    : nextPermitAtNanos;
            nextPermitAtNanos = Math.addExact(scheduledAtNanos, intervalNanos);
            return scheduledAtNanos;
        }
    }
}
