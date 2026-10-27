package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import com.nexus.screenshot.metrics.ScreenshotMetrics;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.quota.service.ApplicationQuotaService;

import java.net.URI;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.BUSY;
import static com.nexus.screenshot.metrics.ScreenshotMetrics.ExecutionMode.SYNC;
import static com.nexus.screenshot.metrics.ScreenshotMetrics.ExecutionOutcome.FAILED;
import static com.nexus.screenshot.metrics.ScreenshotMetrics.ExecutionOutcome.SUCCEEDED;

@Service
public class ScreenshotService {
    private final ScreenshotUrlPolicy policy;
    private final ScreenshotRenderer renderer;
    private final ApplicationQuotaService quotaService;
    private final ScreenshotExecutionGate executionGate;
    private final ScreenshotMetrics metrics;

    public ScreenshotService(ScreenshotUrlPolicy policy, ScreenshotRenderer renderer,
                             ApplicationQuotaService quotaService,
                             ScreenshotExecutionGate executionGate,
                             ScreenshotMetrics metrics) {
        this.policy = policy;
        this.renderer = renderer;
        this.quotaService = quotaService;
        this.executionGate = executionGate;
        this.metrics = metrics;
    }

    public ScreenshotResult capture(ApiKeyIdentity identity, String rawUrl) {
        URI uri = policy.requirePage(rawUrl);
        if (!executionGate.tryAcquireForSynchronousRequest()) throw new ScreenshotException(BUSY);
        Timer.Sample executionSample = null;
        ScreenshotMetrics.ExecutionOutcome outcome = FAILED;
        try {
            executionSample = metrics.startExecution();
            quotaService.consumeOnce(identity);
            ScreenshotResult result = renderer.render(uri);
            outcome = SUCCEEDED;
            return result;
        } finally {
            try {
                if (executionSample != null) {
                    metrics.stopExecution(executionSample, SYNC, outcome);
                }
            } finally {
                executionGate.release();
            }
        }
    }
}
