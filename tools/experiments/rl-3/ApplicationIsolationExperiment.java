import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Experiment 10 / RL-3: two Applications on one observed IP remain independent.
 *
 * This deterministic identity experiment contains no real credentials, networking,
 * concurrency, queue capacity, timeout, Spring or database behavior.
 */
public class ApplicationIsolationExperiment {
    private static final Duration PERMIT_INTERVAL = Duration.ofMillis(100);
    private static final long ARRIVAL = ms(10_000);

    public static void main(String[] args) {
        RequestIdentity applicationA = new RequestIdentity(101, 9001, "203.0.113.9");
        RequestIdentity applicationB = new RequestIdentity(201, 9002, "203.0.113.9");

        demonstrateWhyIpBucketsCoupleIndependentApplications(applicationA, applicationB);
        verifyApplicationBucketsRemainIndependent(applicationA, applicationB);
        System.out.println("RL-3 PASS: two Applications on one IP used independent schedules.");
    }

    private static void demonstrateWhyIpBucketsCoupleIndependentApplications(
            RequestIdentity applicationA, RequestIdentity applicationB) {
        BucketedSchedules wrongPerIpDesign =
                new BucketedSchedules(RequestIdentity::observedClientIp);

        Reservation first = wrongPerIpDesign.reserve(applicationA, ARRIVAL);
        Reservation unrelatedApplication = wrongPerIpDesign.reserve(applicationB, ARRIVAL);

        assertEquals(0, toMs(first.delayNanos()), "per-IP first Application delay");
        assertEquals(100, toMs(unrelatedApplication.delayNanos()),
                "per-IP unrelated Application delay");
        assertEquals(1, wrongPerIpDesign.bucketCount(), "per-IP bucket count");

        System.out.println("Scenario A - wrong business bucket key: observedClientIp");
        print("Application A", applicationA, first);
        print("Application B", applicationB, unrelatedApplication);
        System.out.println("bucketCount=" + wrongPerIpDesign.bucketCount()
                + " -> one Application delayed the other because they shared an IP");
    }

    private static void verifyApplicationBucketsRemainIndependent(
            RequestIdentity applicationA, RequestIdentity applicationB) {
        BucketedSchedules perApplicationDesign = new BucketedSchedules(
                identity -> Long.toString(identity.applicationId()));

        Reservation firstA = perApplicationDesign.reserve(applicationA, ARRIVAL);
        Reservation firstB = perApplicationDesign.reserve(applicationB, ARRIVAL);
        Reservation secondA = perApplicationDesign.reserve(applicationA, ARRIVAL);
        Reservation secondB = perApplicationDesign.reserve(applicationB, ARRIVAL);

        assertEquals(0, toMs(firstA.delayNanos()), "Application A first delay");
        assertEquals(0, toMs(firstB.delayNanos()), "Application B first delay");
        assertEquals(100, toMs(secondA.delayNanos()), "Application A second delay");
        assertEquals(100, toMs(secondB.delayNanos()), "Application B second delay");
        assertEquals(2, perApplicationDesign.bucketCount(), "Application bucket count");

        System.out.println("Scenario B - chosen business bucket key: authenticated applicationId");
        print("Application A first", applicationA, firstA);
        print("Application B first", applicationB, firstB);
        print("Application A second", applicationA, secondA);
        print("Application B second", applicationB, secondB);
        System.out.println("bucketCount=" + perApplicationDesign.bucketCount()
                + " -> each Application advanced only its own next-permit time");
    }

    private static void print(
            String label, RequestIdentity identity, Reservation reservation) {
        System.out.printf(
                "%s applicationId=%d observedIp=%s bucketId=%s scheduled=%dms delay=%dms%n",
                label,
                identity.applicationId(),
                identity.observedClientIp(),
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

    private record RequestIdentity(long apiKeyId, long applicationId, String observedClientIp) {
    }

    private record Reservation(String bucketId, long arrivedAtNanos, long scheduledAtNanos) {
        long delayNanos() {
            return scheduledAtNanos - arrivedAtNanos;
        }
    }

    private static final class BucketedSchedules {
        private final Function<RequestIdentity, String> bucketSelector;
        private final Map<String, SmoothSchedule> schedules = new HashMap<>();

        private BucketedSchedules(Function<RequestIdentity, String> bucketSelector) {
            this.bucketSelector = bucketSelector;
        }

        Reservation reserve(RequestIdentity identity, long nowNanos) {
            String bucketId = bucketSelector.apply(identity);
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
