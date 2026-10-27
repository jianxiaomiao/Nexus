package com.nexus.openapi.ratelimit;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Objects;

public final class ApplicationRateLimiterMetrics
        implements MeterBinder {

    static final String WAITERS_METRIC =
            "nexus.ratelimit.waiters";

    static final String STATES_METRIC =
            "nexus.ratelimit.states";

    private final ApplicationRateLimiter rateLimiter;

    public ApplicationRateLimiterMetrics(
            ApplicationRateLimiter rateLimiter) {
        this.rateLimiter =
                Objects.requireNonNull(rateLimiter);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        WAITERS_METRIC,
                        rateLimiter,
                        ApplicationRateLimiter::currentWaiterCount)
                .description(
                        "Current number of requests occupying "
                                + "rate-limit waiting slots")
                .register(registry);

        Gauge.builder(
                        STATES_METRIC,
                        rateLimiter,
                        ApplicationRateLimiter::currentStateCount)
                .description(
                        "Current number of application rate-limit states")
                .register(registry);
    }
}
