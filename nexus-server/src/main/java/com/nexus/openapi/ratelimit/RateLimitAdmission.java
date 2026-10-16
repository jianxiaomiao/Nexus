package com.nexus.openapi.ratelimit;

import java.util.concurrent.CompletionStage;

public sealed interface RateLimitAdmission {

    record Allowed() implements RateLimitAdmission {
    }

    record Queued(WaitHandle handle) implements RateLimitAdmission {
    }

    record Rejected(RejectReason reason) implements RateLimitAdmission {
    }

    interface WaitHandle {
        CompletionStage<QueueDecision> completion();

        boolean cancel();
    }

    enum RejectReason {
        APPLICATION_QUEUE_FULL,
        GLOBAL_QUEUE_FULL,
        STATE_CAPACITY_REACHED,
        LIMITER_UNAVAILABLE
    }
}
