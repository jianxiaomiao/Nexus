package com.nexus.webhook.dispatch;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class WebhookResponseHandlerTests {
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-14T12:00:00Z"), ZoneOffset.UTC);
    private final WebhookRetryPolicy policy = new WebhookRetryPolicy(5, Duration.ofSeconds(1), Duration.ofMinutes(5));
    private final WebhookResponseHandler handler = new WebhookResponseHandler(policy, clock, n -> n - 1);

    @Test void statusesAndRefreshAreExplicit() {
        assertEquals(DeliveryStatus.DELIVERED, handler.classify(200, null, 0, false).status());
        for (int status : new int[]{201, 204, 301, 307, 308, 400, 403, 404, 499})
            assertEquals(DeliveryStatus.DEAD, handler.classify(status, null, 0, false).status());
        for (int status : new int[]{408, 425, 429, 500, 503, 599}) {
            assertEquals(DeliveryStatus.RETRY, handler.classify(status, null, 4, false).status());
            assertEquals(DeliveryStatus.DEAD, handler.classify(status, null, 5, false).status());
        }
        assertEquals(DeliveryStatus.REFRESH_SECRET, handler.classify(401, null, 5, false).status());
        assertEquals(DeliveryStatus.DEAD, handler.classify(401, null, 0, true).status());
        assertThrows(IllegalArgumentException.class, () -> handler.classify(500, null, -1, false));
        for (int status = 500; status <= 599; status++) assertEquals(DeliveryStatus.RETRY, handler.classify(status, null, 4, false).status());
        var noRetries = new WebhookResponseHandler(new WebhookRetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(2)), clock, n -> 0);
        assertEquals(DeliveryStatus.DEAD, noRetries.classify(500, null, 0, false).status());
        assertEquals(DeliveryStatus.REFRESH_SECRET, noRetries.classify(401, null, 0, false).status());
    }

    @Test void backoffIsExponentialJitterAndBoundedEvenForLargeCounter() {
        assertEquals(Duration.ofSeconds(1), handler.classify(500, null, 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(16), handler.classify(500, null, 4, false).retryDelay());
        var large = new WebhookResponseHandler(new WebhookRetryPolicy(Integer.MAX_VALUE, Duration.ofSeconds(1), Duration.ofMinutes(5)), clock, n -> n - 1);
        assertEquals(Duration.ofMinutes(5), large.classify(500, null, Integer.MAX_VALUE - 1, false).retryDelay());
        var zero = new WebhookResponseHandler(policy, clock, n -> 0);
        assertEquals(Duration.ZERO, zero.classify(500, null, 0, false).retryDelay());
        var extreme = new WebhookResponseHandler(new WebhookRetryPolicy(2, Duration.ofMillis(Long.MAX_VALUE - 1), Duration.ofMillis(Long.MAX_VALUE - 1)), clock, n -> n - 1);
        assertEquals(Duration.ofMillis(Long.MAX_VALUE - 1), extreme.classify(500, null, 1, false).retryDelay());
    }

    @Test void retryAfterSupportsDatesIntegersFallbackAndCaps() {
        assertEquals(Duration.ofSeconds(42), handler.classify(429, "42", 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(30), handler.classify(429, "Wed, 14 Oct 2026 12:00:30 GMT", 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(30), handler.classify(429, "Wednesday, 14-Oct-26 12:00:30 GMT", 0, false).retryDelay());
        assertEquals(Duration.ZERO, handler.classify(429, "Sunday, 06-Nov-94 08:49:37 GMT", 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(30), handler.classify(429, "Wed Oct 14 12:00:30 2026", 0, false).retryDelay());
        assertEquals(Duration.ZERO, handler.classify(429, "Wed, 14 Oct 2026 11:59:30 GMT", 0, false).retryDelay());
        assertEquals(Duration.ofMinutes(5), handler.classify(429, "99999999999999999999999999", 0, false).retryDelay());
        assertEquals(Duration.ofMinutes(5), handler.classify(429, "9".repeat(1000), 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(1), handler.classify(429, "0".repeat(200) + "1", 0, false).retryDelay());
        assertEquals(Duration.ofMinutes(5), handler.classify(429, "Wed, 14 Oct 2026 13:00:00 GMT", 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(1), handler.classify(429, "-3", 0, false).retryDelay());
        assertEquals(Duration.ZERO, handler.classify(429, "0", 0, false).retryDelay());
        for (String malformed : new String[]{null, "", "garbage", "1.5", "+1", "Wed, 99 Oct 2026 12:00:00 GMT"})
            assertEquals(Duration.ofSeconds(1), handler.classify(429, malformed, 0, false).retryDelay());
        assertEquals(Duration.ofSeconds(1), handler.classify(500, "42", 0, false).retryDelay());
        var fractionalCap = new WebhookResponseHandler(new WebhookRetryPolicy(1, Duration.ofMillis(1), Duration.ofMillis(1500)), clock, n -> 0);
        assertEquals(Duration.ofSeconds(1), fractionalCap.classify(429, "1", 0, false).retryDelay());
        assertEquals(Duration.ofMillis(1500), fractionalCap.classify(429, "2", 0, false).retryDelay());
    }

    @Test void dateRoundingRandomAndClockFailuresAreExplicit() {
        var fractionClock = Clock.fixed(Instant.parse("2026-10-14T12:00:00.999999999Z"), ZoneOffset.UTC);
        var fractional = new WebhookResponseHandler(policy, fractionClock, n -> 0);
        assertEquals(Duration.ofMillis(1), fractional.classify(429, "Wed, 14 Oct 2026 12:00:01 GMT", 0, false).retryDelay());
        var badRandom = new WebhookResponseHandler(policy, clock, n -> n);
        assertThrows(IllegalStateException.class, () -> badRandom.classify(500, null, 0, false));
        Clock badClock = new Clock() {
            public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(java.time.ZoneId zone) { return this; }
            public Instant instant() { throw new IllegalStateException("clock failed"); }
        };
        assertThrows(IllegalStateException.class, () -> new WebhookResponseHandler(policy, badClock, n -> 0).classify(429, "not a date", 0, false));
    }

    @Test void invalidPoliciesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryPolicy(-1, Duration.ofSeconds(1), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryPolicy(1, Duration.ZERO, Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryPolicy(1, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new WebhookRetryPolicy(1, Duration.ofSeconds(Long.MAX_VALUE), Duration.ofSeconds(Long.MAX_VALUE)));
    }
}
