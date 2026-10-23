package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.springframework.stereotype.Service;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.quota.service.ApplicationQuotaService;

import java.net.URI;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.BUSY;

@Service
public class ScreenshotService {
    private final ScreenshotUrlPolicy policy;
    private final ScreenshotRenderer renderer;
    private final ApplicationQuotaService quotaService;
    private final ScreenshotExecutionGate executionGate;

    public ScreenshotService(ScreenshotUrlPolicy policy, ScreenshotRenderer renderer,
                             ApplicationQuotaService quotaService, ScreenshotExecutionGate executionGate) {
        this.policy = policy;
        this.renderer = renderer;
        this.quotaService = quotaService;
        this.executionGate = executionGate;
    }

    public ScreenshotResult capture(ApiKeyIdentity identity, String rawUrl) {
        URI uri = policy.requirePage(rawUrl);
        if (!executionGate.tryAcquireForSynchronousRequest()) throw new ScreenshotException(BUSY);
        try {
            quotaService.consumeOnce(identity);
            return renderer.render(uri);
        } finally {
            executionGate.release();
        }
    }
}
