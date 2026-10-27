package com.nexus.usage.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public final class UsageRecordMetrics {
    public static final String FAILURE_METRIC_NAME =
            "nexus.usage.record.failures";

    private final Map<FailureReason, Counter> failureCounters;

    public UsageRecordMetrics(MeterRegistry meterRegistry) {
        Objects.requireNonNull(meterRegistry);
        this.failureCounters = new EnumMap<>(FailureReason.class);
        for (FailureReason reason : FailureReason.values()) {
            failureCounters.put(reason, Counter.builder(FAILURE_METRIC_NAME)
                    .description("Usage events that could not be confirmed as recorded")
                    .tag("reason", reason.tagValue())
                    .register(meterRegistry));
        }
    }

    public void recordFailure(FailureReason reason) {
        failureCounters.get(Objects.requireNonNull(reason)).increment();
    }

    public enum FailureReason {
        QUEUE_REJECTED("queue_rejected"),
        TRANSIENT_WRITE_EXHAUSTED("transient_write_exhausted"),
        DUPLICATE_UNCONFIRMED("duplicate_unconfirmed"),
        WRITE_FAILED("write_failed");

        private final String tagValue;

        FailureReason(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
