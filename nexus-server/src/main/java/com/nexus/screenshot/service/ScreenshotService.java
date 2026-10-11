package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.concurrent.Semaphore;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.BUSY;

@Service
public class ScreenshotService {
    private final ScreenshotUrlPolicy policy;
    private final ScreenshotRenderer renderer;
    // 本机同时只运行一个截图任务；不排无限队列，不创建无界浏览器进程。
    private final Semaphore slot = new Semaphore(1);

    public ScreenshotService(ScreenshotUrlPolicy policy, ScreenshotRenderer renderer) { this.policy = policy; this.renderer = renderer; }

    public ScreenshotResult capture(String rawUrl) {
        URI uri = policy.requirePage(rawUrl);
        if (!slot.tryAcquire()) throw new ScreenshotException(BUSY);
        try { return renderer.render(uri); } finally { slot.release(); }
    }
}
