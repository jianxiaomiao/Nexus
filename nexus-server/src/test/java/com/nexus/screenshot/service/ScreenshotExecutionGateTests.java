package com.nexus.screenshot.service;

import com.nexus.screenshot.metrics.ScreenshotExecutionGateMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class ScreenshotExecutionGateTests {
    @Test
    void busyGaugeReadsTheAuthoritativeSemaphoreState() {
        ScreenshotExecutionGate gate = new ScreenshotExecutionGate();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new ScreenshotExecutionGateMetrics(gate).bindTo(registry);
        var gauge = registry.get(
                ScreenshotExecutionGateMetrics.SLOT_BUSY_METRIC_NAME).gauge();

        assertEquals(0.0, gauge.value());
        assertTrue(gate.tryAcquireForSynchronousRequest());
        assertEquals(1.0, gauge.value());
        gate.release();
        assertEquals(0.0, gauge.value());
    }

    @Test
    void waitingAsynchronousJobCannotBeOvertakenByLaterSynchronousRequest() throws Exception {
        ScreenshotExecutionGate gate = new ScreenshotExecutionGate();
        assertTrue(gate.tryAcquireForSynchronousRequest());

        CountDownLatch asynchronousAcquired = new CountDownLatch(1);
        CountDownLatch releaseAsynchronous = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> waiter = executor.submit(() -> {
                try {
                    gate.acquireForAsynchronousJob();
                    asynchronousAcquired.countDown();
                    releaseAsynchronous.await(3, TimeUnit.SECONDS);
                    gate.release();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
            });

            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
                while (!gate.hasQueuedThreads()) {
                    Thread.onSpinWait();
                }
            });

            gate.release();
            boolean synchronousBarged = gate.tryAcquireForSynchronousRequest();
            if (synchronousBarged) {
                gate.release();
            }
            assertFalse(synchronousBarged,
                    "a new synchronous request must not barge ahead of the queued worker");
            assertTrue(asynchronousAcquired.await(2, TimeUnit.SECONDS));

            releaseAsynchronous.countDown();
            waiter.get(3, TimeUnit.SECONDS);
        } finally {
            releaseAsynchronous.countDown();
        }

        assertTrue(gate.tryAcquireForSynchronousRequest());
        gate.release();
    }
}
