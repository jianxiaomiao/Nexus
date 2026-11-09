package com.nexus.screenshot.service;

import com.nexus.screenshot.exception.ScreenshotException;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import static com.nexus.screenshot.exception.ScreenshotException.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

class ScreenshotBudgetTests {
    @Test
    void timeLimitExpiresAtEquality() {
        AtomicLong clock = new AtomicLong();
        var budget = new ScreenshotBudget(clock::get, Duration.ofMillis(10), 2, 10);
        assertEquals(10, budget.remainingMillis());
        clock.set(10_000_000);
        assertEquals(TIMEOUT, assertThrows(ScreenshotException.class, budget::remainingMillis).kind());
    }
    @Test
    void millisecondViewRoundsUpWithoutMovingTheNanosecondDeadlineEarlier() {
        AtomicLong clock = new AtomicLong();
        var budget = new ScreenshotBudget(clock::get, Duration.ofNanos(1_000_001), 1, 1);
        assertEquals(2, budget.remainingMillis());
        assertEquals(1_000_001, budget.remainingNanos());
        clock.set(1_000_000);
        assertEquals(1, budget.remainingMillis());
        assertEquals(1, budget.remainingNanos());
        clock.incrementAndGet();
        assertEquals(TIMEOUT, assertThrows(ScreenshotException.class, budget::remainingNanos).kind());
    }
    @Test
    void requestAndByteLimitsPermitExactBoundary() {
        var budget = new ScreenshotBudget(() -> 0, Duration.ofSeconds(1), 2, 10);
        budget.requestStarted(); budget.requestStarted(); budget.bytesRead(10);
        var requests = assertThrows(ScreenshotException.class, budget::requestStarted);
        assertEquals(LIMIT_EXCEEDED, requests.kind());
        assertEquals(new ScreenshotException.Diagnostic(ScreenshotException.DiagnosticReason.REQUEST_COUNT, 3, 2), requests.diagnostic());
        var bytes = assertThrows(ScreenshotException.class, () -> budget.bytesRead(1));
        assertEquals(LIMIT_EXCEEDED, bytes.kind());
        assertEquals(new ScreenshotException.Diagnostic(ScreenshotException.DiagnosticReason.TOTAL_BYTES, 11, 10), bytes.diagnostic());
    }
    @Test
    void budgetsArePerScreenshot() {
        var first = new ScreenshotBudget(() -> 0, Duration.ofSeconds(1), 1, 1);
        var second = new ScreenshotBudget(() -> 0, Duration.ofSeconds(1), 1, 1);
        first.requestStarted(); first.bytesRead(1);
        assertDoesNotThrow(() -> { second.requestStarted(); second.bytesRead(1); });
    }
}
