package com.nexus.webhook.dispatch;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Retry delays use whole milliseconds. maxRetries counts ordinary retries, not attempts. */
@ConfigurationProperties(prefix = "nexus.webhook.retry")
public record WebhookRetryPolicy(@DefaultValue("5") int maxRetries,
        @DefaultValue("1s") Duration baseBackoff, @DefaultValue("5m") Duration maxBackoff) {
    public WebhookRetryPolicy {
        Objects.requireNonNull(baseBackoff, "baseBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (maxRetries < 0) throw new IllegalArgumentException("maxRetries must be nonnegative");
        try {
            long base = baseBackoff.toMillis(), max = maxBackoff.toMillis();
            if (base < 1 || max < base || max == Long.MAX_VALUE
                    || !baseBackoff.equals(Duration.ofMillis(base)) || !maxBackoff.equals(Duration.ofMillis(max)))
                throw new IllegalArgumentException("backoffs must be whole milliseconds, positive and ordered");
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException("backoff is too large", e);
        }
    }

}
