package com.nexus.openapi.ratelimit;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

public final class ApplicationRateMetrics {
    public static final String ADMISSION_METRIC_NAME =
            "nexus.ratelimit.admission";

    private final MeterRegistry meterRegistry;
    private final Map<AdmissionOutcome, Timer> admissionTimers;

    public ApplicationRateMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.admissionTimers = new EnumMap<>(AdmissionOutcome.class);

        for (AdmissionOutcome outcome : AdmissionOutcome.values()) {
            admissionTimers.put(outcome, Timer.builder(ADMISSION_METRIC_NAME)
                    .description("Time from rate-limit admission attempt to final decision")
                    .tag("outcome", outcome.tagValue())
                    .register(meterRegistry));
        }
    }

    public Timer.Sample startAdmission() {
        return Timer.start(meterRegistry);
    }

    public void stopAdmission(
            Timer.Sample sample,
            AdmissionOutcome outcome) {
        Objects.requireNonNull(sample).stop(
                admissionTimers.get(Objects.requireNonNull(outcome)));
    }

    public enum AdmissionOutcome {
        ALLOWED_IMMEDIATE("allowed_immediate"),
        ALLOWED_AFTER_WAIT("allowed_after_wait"),
        APPLICATION_QUEUE_FULL("application_queue_full"),
        GLOBAL_QUEUE_FULL("global_queue_full"),
        STATE_CAPACITY_REACHED("state_capacity_reached"),
        TIMED_OUT("timed_out"),
        CANCELLED("cancelled"),
        UNAVAILABLE("unavailable");

        private final String tagValue;

        AdmissionOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
