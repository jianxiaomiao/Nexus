import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Experiment 8 / RL-1: deterministic scheduling for a smooth 10 requests/second rate.
 *
 * This is an isolated learning model. It deliberately has no threads, queue capacity,
 * timeout, HTTP, Spring or database behavior; later experiments add those variables.
 */
public class SmoothRateScheduleExperiment {
    private static final Duration PERMIT_INTERVAL = Duration.ofMillis(100);

    public static void main(String[] args) {
        sameInstantRequestsReceiveEvenlySpacedSlots();
        idleTimeDoesNotAccumulateBurstCredit();
        requestAtExactBoundaryRunsImmediately();
        System.out.println("RL-1 PASS: all deterministic scheduling assertions passed.");
    }

    private static void sameInstantRequestsReceiveEvenlySpacedSlots() {
        SmoothSchedule schedule = new SmoothSchedule(PERMIT_INTERVAL);
        long arrival = ms(10_000);
        List<Reservation> reservations = new ArrayList<>();

        for (int request = 1; request <= 5; request++) {
            reservations.add(schedule.reserve(arrival));
        }

        System.out.println("Scenario A - five requests arrive at virtual t=10000ms:");
        for (int index = 0; index < reservations.size(); index++) {
            Reservation reservation = reservations.get(index);
            long expectedScheduledMs = 10_000L + index * 100L;
            assertEquals(expectedScheduledMs, toMs(reservation.scheduledAtNanos()),
                    "request " + (index + 1) + " scheduled time");
            assertEquals(index * 100L, toMs(reservation.delayNanos()),
                    "request " + (index + 1) + " delay");
            System.out.printf("request=%d arrival=%dms scheduled=%dms delay=%dms%n",
                    index + 1,
                    toMs(reservation.arrivedAtNanos()),
                    toMs(reservation.scheduledAtNanos()),
                    toMs(reservation.delayNanos()));
        }
    }

    private static void idleTimeDoesNotAccumulateBurstCredit() {
        SmoothSchedule schedule = new SmoothSchedule(PERMIT_INTERVAL);

        Reservation first = schedule.reserve(ms(20_000));
        Reservation second = schedule.reserve(ms(20_000));
        Reservation afterIdle = schedule.reserve(ms(25_000));
        Reservation immediatelyAfterIdle = schedule.reserve(ms(25_000));

        assertEquals(20_000, toMs(first.scheduledAtNanos()), "first request after initial idle");
        assertEquals(20_100, toMs(second.scheduledAtNanos()), "second continuous request");
        assertEquals(25_000, toMs(afterIdle.scheduledAtNanos()), "first request after long idle");
        assertEquals(25_100, toMs(immediatelyAfterIdle.scheduledAtNanos()),
                "second request after long idle");

        System.out.println("Scenario B - idle time resets the next slot to now, without burst credit:");
        print("first", first);
        print("second", second);
        print("afterIdle", afterIdle);
        print("immediatelyAfterIdle", immediatelyAfterIdle);
    }

    private static void requestAtExactBoundaryRunsImmediately() {
        SmoothSchedule schedule = new SmoothSchedule(PERMIT_INTERVAL);

        Reservation first = schedule.reserve(ms(30_000));
        Reservation atBoundary = schedule.reserve(ms(30_100));

        assertEquals(0, toMs(first.delayNanos()), "initial request delay");
        assertEquals(0, toMs(atBoundary.delayNanos()), "exact-boundary request delay");
        assertEquals(30_100, toMs(atBoundary.scheduledAtNanos()), "exact-boundary scheduled time");

        System.out.println("Scenario C - a request arriving exactly at the next slot has no wait:");
        print("first", first);
        print("atBoundary", atBoundary);
    }

    private static void print(String label, Reservation reservation) {
        System.out.printf("%s arrival=%dms scheduled=%dms delay=%dms%n",
                label,
                toMs(reservation.arrivedAtNanos()),
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

    private record Reservation(long arrivedAtNanos, long scheduledAtNanos) {
        long delayNanos() {
            return scheduledAtNanos - arrivedAtNanos;
        }
    }

    private static final class SmoothSchedule {
        private final long intervalNanos;
        private Long nextPermitAtNanos;

        private SmoothSchedule(Duration interval) {
            if (interval.isZero() || interval.isNegative()) {
                throw new IllegalArgumentException("interval must be positive");
            }
            this.intervalNanos = interval.toNanos();
        }

        Reservation reserve(long nowNanos) {
            long scheduledAtNanos = nextPermitAtNanos == null || nowNanos >= nextPermitAtNanos
                    ? nowNanos
                    : nextPermitAtNanos;
            nextPermitAtNanos = Math.addExact(scheduledAtNanos, intervalNanos);
            return new Reservation(nowNanos, scheduledAtNanos);
        }
    }
}
