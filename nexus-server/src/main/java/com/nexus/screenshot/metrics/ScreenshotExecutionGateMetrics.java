package com.nexus.screenshot.metrics;

import com.nexus.screenshot.service.ScreenshotExecutionGate;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ScreenshotExecutionGateMetrics implements MeterBinder {
    public static final String SLOT_BUSY_METRIC_NAME =
            "nexus.screenshot.slot.busy";

    private final ScreenshotExecutionGate executionGate;

    public ScreenshotExecutionGateMetrics(ScreenshotExecutionGate executionGate) {
        this.executionGate = Objects.requireNonNull(executionGate);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        SLOT_BUSY_METRIC_NAME,
                        executionGate,
                        ScreenshotExecutionGate::currentBusySlotCount)
                .description("Whether the shared screenshot execution slot is currently busy")
                .register(registry);
    }
}
