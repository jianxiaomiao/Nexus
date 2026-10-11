package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;

import java.time.Duration;
import java.util.function.LongSupplier;

import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static com.nexus.screenshot.exception.ScreenshotException.DiagnosticReason.*;

/** 每次截图独立持有预算；用单调时钟计算耗时，不使用会跳变的墙上时间。 */
public final class ScreenshotBudget {
    public static final int MAX_RESOURCE_BYTES = 2 * 1024 * 1024;
    private final LongSupplier clock;
    private final long deadline;
    private final int maxRequests;
    private final long maxBytes;
    private int requests;
    private long bytes;

    public ScreenshotBudget() { this(System::nanoTime, Duration.ofSeconds(20), 64, 10 * 1024 * 1024); }
    ScreenshotBudget(LongSupplier clock, Duration duration, int maxRequests, long maxBytes) {
        this.clock = clock;
        this.deadline = clock.getAsLong() + duration.toNanos();
        this.maxRequests = maxRequests;
        this.maxBytes = maxBytes;
    }
    public long remainingMillis() {
        long remaining = deadline - clock.getAsLong();
        if (remaining <= 0) throw new ScreenshotException(TIMEOUT);
        return Math.max(1, remaining / 1_000_000);
    }
    public void requestStarted() {
        remainingMillis();
        if (++requests > maxRequests) throw new ScreenshotException(LIMIT_EXCEEDED, REQUEST_COUNT, requests, maxRequests);
    }
    public void bytesRead(int count) {
        remainingMillis();
        if ((bytes += count) > maxBytes) throw new ScreenshotException(LIMIT_EXCEEDED, TOTAL_BYTES, bytes, maxBytes);
    }
}
