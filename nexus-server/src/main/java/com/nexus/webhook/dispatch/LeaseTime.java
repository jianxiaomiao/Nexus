package com.nexus.webhook.dispatch;

import java.time.Duration;

final class LeaseTime {
    private LeaseTime() {}

    static long positiveMicros(Duration duration) {
        if (duration.isZero() || duration.isNegative() || duration.getNano() % 1_000 != 0)
            throw new IllegalArgumentException("duration must be positive and an exact number of microseconds");
        try {
            long micros = Math.addExact(Math.multiplyExact(duration.getSeconds(), 1_000_000L), duration.getNano() / 1_000);
            // Local budgets are measured in nanoseconds.
            Math.multiplyExact(micros, 1_000L);
            return micros;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("duration is too large", e);
        }
    }

    static long budgetNanos(LeaseSnapshot snapshot) {
        if (snapshot == null || snapshot.databaseNow() == null || snapshot.leaseExpiresAt() == null)
            throw new IllegalStateException("missing database lease snapshot");
        Duration remaining = Duration.between(snapshot.databaseNow(), snapshot.leaseExpiresAt());
        if (remaining.isNegative() || remaining.isZero()) return 0;
        return remaining.toNanos();
    }
}
