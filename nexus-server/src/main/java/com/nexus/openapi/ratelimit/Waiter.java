package com.nexus.openapi.ratelimit;

import java.util.concurrent.CompletableFuture;

final class Waiter {

    final CompletableFuture<QueueDecision> result =
            new CompletableFuture<>();

    final long deadlineNanos;

    WaiterStatus status = WaiterStatus.WAITING;

    Waiter(long deadlineNanos) {
        this.deadlineNanos = deadlineNanos;
    }
}
enum WaiterStatus {
    WAITING,
    ALLOWED,
    TIMED_OUT,
    CANCELLED,
    LIMITER_UNAVAILABLE
}
