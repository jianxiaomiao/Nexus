package com.nexus.job.metrics;

import com.nexus.job.mapper.ScreenshotJobMapper;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

import java.util.Objects;

@Component
public final class ScreenshotJobMetrics implements MeterBinder {
    public static final String UNFINISHED_JOBS_METRIC_NAME =
            "nexus.screenshot.jobs.unfinished";

    private final ScreenshotJobMapper jobMapper;

    public ScreenshotJobMetrics(ScreenshotJobMapper jobMapper) {
        this.jobMapper = Objects.requireNonNull(jobMapper);
    }

    @Override
    public void bindTo(MeterRegistry registry) {
        Gauge.builder(
                        UNFINISHED_JOBS_METRIC_NAME,
                        this,
                        ScreenshotJobMetrics::currentUnfinishedJobCount)
                .description("Current number of queued and running screenshot jobs")
                .register(registry);
    }

    double currentUnfinishedJobCount() {
        try {
            return jobMapper.countUnfinished();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
