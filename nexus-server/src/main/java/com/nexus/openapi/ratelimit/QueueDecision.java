package com.nexus.openapi.ratelimit;

public enum QueueDecision {
    ALLOWED,
    TIMED_OUT,
    CANCELLED,
    LIMITER_UNAVAILABLE
}