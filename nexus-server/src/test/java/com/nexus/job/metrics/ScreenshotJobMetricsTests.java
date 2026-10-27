package com.nexus.job.metrics;

import com.nexus.job.mapper.ScreenshotJobMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ScreenshotJobMetricsTests {
    private final ScreenshotJobMapper jobs = mock(ScreenshotJobMapper.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    @Test
    void unfinishedGaugeReadsTheDatabaseCountOnEveryObservation() {
        when(jobs.countUnfinished()).thenReturn(0L, 2L, 0L);
        new ScreenshotJobMetrics(jobs).bindTo(registry);
        var gauge = registry.get(
                ScreenshotJobMetrics.UNFINISHED_JOBS_METRIC_NAME).gauge();

        assertEquals(0.0, gauge.value());
        assertEquals(2.0, gauge.value());
        assertEquals(0.0, gauge.value());
    }

    @Test
    void databaseFailureIsReportedAsUnavailableInsteadOfZero() {
        when(jobs.countUnfinished()).thenThrow(
                new DataAccessResourceFailureException("test-only"));
        new ScreenshotJobMetrics(jobs).bindTo(registry);
        var gauge = registry.get(
                ScreenshotJobMetrics.UNFINISHED_JOBS_METRIC_NAME).gauge();

        assertTrue(Double.isNaN(gauge.value()));
    }
}
