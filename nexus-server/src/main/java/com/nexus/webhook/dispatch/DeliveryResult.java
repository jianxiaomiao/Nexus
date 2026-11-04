package com.nexus.webhook.dispatch;

import java.time.Duration;

/** A decision only; the caller owns all state transitions and retry counters. */
public record DeliveryResult(DeliveryStatus status, String failureCode, Duration retryDelay) {}
