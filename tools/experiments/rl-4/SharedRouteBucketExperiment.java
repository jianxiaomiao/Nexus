import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Experiment 11 / RL-4: all authenticated /v1 operations of one Application
 * share a single smooth-rate schedule, regardless of endpoint or Quota charging.
 */
public class SharedRouteBucketExperiment {
    private static final Duration PERMIT_INTERVAL = Duration.ofMillis(100);
    private static final long ARRIVAL = ms(10_000);

    public static void main(String[] args) {
        List<MachineRequest> requests = List.of(
                new MachineRequest(9001, "utils.uuid", "GET", "/v1/utils/uuid", true),
                new MachineRequest(9001, "shortlink.list", "GET", "/v1/short-links", false),
                new MachineRequest(9001, "web.screenshot", "POST", "/v1/web/screenshot", true),
                new MachineRequest(9001, "shortlink.update", "PUT", "/v1/short-links", false),
                new MachineRequest(9001, "utils.hash", "POST", "/v1/utils/hash", true),
                new MachineRequest(9001, "shortlink.delete", "DELETE", "/v1/short-links/77", false),
                new MachineRequest(9001, "web.extract", "POST", "/v1/web/extract", true),
                new MachineRequest(9001, "shortlink.create", "POST", "/v1/short-links", true)
        );

        demonstrateWhyPerApiBucketsAllowRouteSwitching(requests);
        verifyEveryMachineRouteSharesTheApplicationBucket(requests);
        System.out.println("RL-4 PASS: all sampled /v1 operations shared one Application schedule.");
    }

    private static void demonstrateWhyPerApiBucketsAllowRouteSwitching(
            List<MachineRequest> requests) {
        BucketedSchedules wrongPerApiDesign = new BucketedSchedules(
                request -> request.applicationId() + ":" + request.apiCode());

        System.out.println("Scenario A - wrong bucket key: applicationId + apiCode");
        for (MachineRequest request : requests) {
            Reservation reservation = wrongPerApiDesign.reserve(request, ARRIVAL);
            assertEquals(0, toMs(reservation.delayNanos()),
                    "per-API delay for " + request.apiCode());
            print(request, reservation);
        }
        assertEquals(requests.size(), wrongPerApiDesign.bucketCount(),
                "per-API bucket count");
        System.out.println("bucketCount=" + wrongPerApiDesign.bucketCount()
                + " -> switching endpoints obtained another immediate slot each time");
    }

    private static void verifyEveryMachineRouteSharesTheApplicationBucket(
            List<MachineRequest> requests) {
        BucketedSchedules perApplicationDesign = new BucketedSchedules(
                request -> Long.toString(request.applicationId()));

        System.out.println("Scenario B - chosen bucket key: authenticated applicationId only");
        for (int index = 0; index < requests.size(); index++) {
            MachineRequest request = requests.get(index);
            Reservation reservation = perApplicationDesign.reserve(request, ARRIVAL);
            long expectedDelayMs = index * 100L;
            assertEquals(expectedDelayMs, toMs(reservation.delayNanos()),
                    "Application delay for " + request.apiCode());
            assertEquals(10_000L + expectedDelayMs, toMs(reservation.scheduledAtNanos()),
                    "Application scheduled time for " + request.apiCode());
            print(request, reservation);
        }
        assertEquals(1, perApplicationDesign.bucketCount(), "Application bucket count");
        System.out.println("bucketCount=" + perApplicationDesign.bucketCount()
                + " -> endpoint and Quota policy did not create extra rate buckets");
    }

    private static void print(MachineRequest request, Reservation reservation) {
        System.out.printf(
                "apiCode=%s method=%s path=%s quotaCharged=%s bucketId=%s scheduled=%dms delay=%dms%n",
                request.apiCode(),
                request.method(),
                request.path(),
                request.quotaCharged(),
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

    private record MachineRequest(
            long applicationId,
            String apiCode,
            String method,
            String path,
            boolean quotaCharged) {
    }

    private record Reservation(String bucketId, long arrivedAtNanos, long scheduledAtNanos) {
        long delayNanos() {
            return scheduledAtNanos - arrivedAtNanos;
        }
    }

    private static final class BucketedSchedules {
        private final Function<MachineRequest, String> bucketSelector;
        private final Map<String, SmoothSchedule> schedules = new HashMap<>();

        private BucketedSchedules(Function<MachineRequest, String> bucketSelector) {
            this.bucketSelector = bucketSelector;
        }

        Reservation reserve(MachineRequest request, long nowNanos) {
            String bucketId = bucketSelector.apply(request);
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
