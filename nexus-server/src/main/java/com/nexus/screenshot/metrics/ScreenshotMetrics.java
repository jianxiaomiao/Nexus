package com.nexus.screenshot.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

@Component
public final class ScreenshotMetrics {
    public static final String QUEUE_DELAY_METRIC_NAME =
            "nexus.screenshot.queue.delay";
    public static final String EXECUTION_METRIC_NAME =
            "nexus.screenshot.execution";

    private final MeterRegistry meterRegistry;
    private final Timer queueDelayTimer;
    private final Map<ExecutionMode, Map<ExecutionOutcome, Timer>> executionTimers;

    public ScreenshotMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = Objects.requireNonNull(meterRegistry);
        this.queueDelayTimer = Timer.builder(QUEUE_DELAY_METRIC_NAME)
                .description("Time from screenshot job creation to its first execution claim")
                .register(meterRegistry);
        this.executionTimers = new EnumMap<>(ExecutionMode.class);

        for (ExecutionMode mode : ExecutionMode.values()) {
            Map<ExecutionOutcome, Timer> timersByOutcome =
                    new EnumMap<>(ExecutionOutcome.class);
            for (ExecutionOutcome outcome : ExecutionOutcome.values()) {
                timersByOutcome.put(outcome, Timer.builder(EXECUTION_METRIC_NAME)
                        .description("Time holding the shared screenshot execution slot")
                        .tag("mode", mode.tagValue())
                        .tag("outcome", outcome.tagValue())
                        .register(meterRegistry));
            }
            executionTimers.put(mode, timersByOutcome);
        }
    }

    /**
     * Queue delay begins before this JVM may observe the job, so it is derived
     * from the two persisted UTC timestamps rather than an in-memory Sample.
     */
    public void recordQueueDelay(LocalDateTime createdAt, LocalDateTime startedAt) {
        Objects.requireNonNull(createdAt);
        Objects.requireNonNull(startedAt);
        Duration delay = Duration.between(createdAt, startedAt);
        if (!delay.isNegative()) {
            queueDelayTimer.record(delay);
        }
    }

    public Timer.Sample startExecution() {
        return Timer.start(meterRegistry);
    }

    public void stopExecution(Timer.Sample sample,
                              ExecutionMode mode,
                              ExecutionOutcome outcome) {
        Objects.requireNonNull(sample).stop(
                executionTimers.get(Objects.requireNonNull(mode))
                        .get(Objects.requireNonNull(outcome)));
    }

    public enum ExecutionMode {
        SYNC("sync"),
        ASYNC("async");

        private final String tagValue;

        ExecutionMode(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }

    public enum ExecutionOutcome {
        SUCCEEDED("succeeded"),
        FAILED("failed");

        private final String tagValue;

        ExecutionOutcome(String tagValue) {
            this.tagValue = tagValue;
        }

        public String tagValue() {
            return tagValue;
        }
    }
}
