package com.nexus.webhook.dispatch;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Shared scheduler, independent of the worker pool. */
public final class LeaseScheduler implements AutoCloseable {
    private final ScheduledThreadPoolExecutor executor;
    private final ScheduledThreadPoolExecutor deadlines;

    public LeaseScheduler() {
        AtomicInteger sequence = new AtomicInteger();
        executor = new ScheduledThreadPoolExecutor(4, task ->
                new Thread(task, "webhook-lease-" + sequence.incrementAndGet()));
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        executor.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        deadlines = new ScheduledThreadPoolExecutor(1, task -> new Thread(task, "webhook-lease-deadline"));
        deadlines.setRemoveOnCancelPolicy(true);
        deadlines.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    ScheduledFuture<?> schedule(Runnable task, Duration interval) {
        return executor.scheduleWithFixedDelay(task, interval.toNanos(), interval.toNanos(), TimeUnit.NANOSECONDS);
    }

    ScheduledFuture<?> deadline(Runnable task, long nanos) {
        return deadlines.schedule(task, Math.max(0, nanos), TimeUnit.NANOSECONDS);
    }

    @Override public void close() { deadlines.shutdownNow(); executor.shutdownNow(); }
}
