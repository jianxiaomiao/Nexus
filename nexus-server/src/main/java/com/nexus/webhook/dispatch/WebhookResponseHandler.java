package com.nexus.webhook.dispatch;

import java.time.Clock;
import java.time.Duration;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongUnaryOperator;
import org.apache.hc.client5.http.utils.DateUtils;

/** Classifies responses without performing persistence, credential refresh, or network I/O. */
public final class WebhookResponseHandler {
    private final WebhookRetryPolicy policy;
    private final Clock clock;
    private final LongUnaryOperator randomBelow;

    public WebhookResponseHandler(WebhookRetryPolicy policy, Clock clock) {
        this(policy, clock, bound -> ThreadLocalRandom.current().nextLong(bound));
    }

    public WebhookResponseHandler(WebhookRetryPolicy policy, Clock clock, LongUnaryOperator randomBelow) {
        this.policy = Objects.requireNonNull(policy);
        this.clock = Objects.requireNonNull(clock);
        this.randomBelow = Objects.requireNonNull(randomBelow);
    }

    public DeliveryResult classify(WebhookHttpResponse response, int ordinaryRetriesUsed, boolean secretRefreshed) {
        Objects.requireNonNull(response);
        return classify(response.statusCode(), response.retryAfter(), ordinaryRetriesUsed, secretRefreshed);
    }

    /** ordinaryRetriesUsed excludes the initial attempt and the one-time secret refresh. */
    public DeliveryResult classify(int status, String retryAfter, int ordinaryRetriesUsed, boolean secretRefreshed) {
        if (ordinaryRetriesUsed < 0) throw new IllegalArgumentException("negative retry count");
        if (status == 200) return new DeliveryResult(DeliveryStatus.DELIVERED, null, null);
        if (status == 401) return new DeliveryResult(secretRefreshed ? DeliveryStatus.DEAD : DeliveryStatus.REFRESH_SECRET,
                secretRefreshed ? "HTTP_401_REFRESH_EXHAUSTED" : "HTTP_401", null);
        boolean retryable = status == 408 || status == 425 || status == 429 || status >= 500 && status <= 599;
        if (!retryable) return new DeliveryResult(DeliveryStatus.DEAD, "HTTP_UNSUPPORTED_" + status, null);
        if (ordinaryRetriesUsed >= policy.maxRetries()) return new DeliveryResult(DeliveryStatus.DEAD, "HTTP_RETRIES_EXHAUSTED", null);
        Duration delay = status == 429 ? parseRetryAfter(retryAfter) : null;
        if (delay == null) delay = jitter(ordinaryRetriesUsed);
        return new DeliveryResult(DeliveryStatus.RETRY, "HTTP_" + status, delay);
    }

    public boolean retriesExhausted(long ordinaryRetriesUsed) { return ordinaryRetriesUsed >= policy.maxRetries(); }

    public DeliveryResult retryableFailure(String code, int ordinaryRetriesUsed) {
        if (ordinaryRetriesUsed >= policy.maxRetries()) return new DeliveryResult(DeliveryStatus.DEAD, code, null);
        return new DeliveryResult(DeliveryStatus.RETRY, code, jitter(ordinaryRetriesUsed));
    }

    public Duration retryDelay(int ordinaryRetriesUsed) { return jitter(ordinaryRetriesUsed); }

    private Duration jitter(int used) {
        long max = policy.maxBackoff().toMillis(), bound = policy.baseBackoff().toMillis();
        for (int i = 0; i < used && bound < max; i++) bound = bound > max / 2 ? max : bound * 2;
        long value = randomBelow.applyAsLong(bound + 1);
        if (value < 0 || value > bound) throw new IllegalStateException("random source returned out-of-range value");
        return Duration.ofMillis(value);
    }

    private Duration parseRetryAfter(String value) {
        if (value == null || value.isBlank()) return null;
        String input = value.trim();
        long max = policy.maxBackoff().toMillis();
        if (input.matches("[0-9]+")) {
            int first = 0;
            while (first < input.length() - 1 && input.charAt(first) == '0') first++;
            input = input.substring(first);
            if (input.length() > 19) return Duration.ofMillis(max);
            try {
                long seconds = Long.parseLong(input);
                return Duration.ofMillis(seconds > max / 1000 ? max : seconds * 1000);
            } catch (NumberFormatException e) { return Duration.ofMillis(max); }
        }
        if (input.length() > 128) return null;
        Instant now = clock.instant();
        try {
            Instant when;
            if (input.matches("[A-Za-z]+, [0-9]{2}-[A-Za-z]{3}-[0-9]{2} [0-9]{2}:[0-9]{2}:[0-9]{2} GMT")) {
                int comma = input.indexOf(',');
                LocalDateTime date = LocalDateTime.parse(input.substring(comma + 2),
                        DateTimeFormatter.ofPattern("dd-MMM-yy HH:mm:ss 'GMT'", Locale.US));
                if (date.isAfter(LocalDateTime.ofInstant(now, ZoneOffset.UTC).plusYears(50))) date = date.minusYears(100);
                if (!date.format(DateTimeFormatter.ofPattern("EEEE", Locale.US)).equals(input.substring(0, comma))) return null;
                when = date.toInstant(ZoneOffset.UTC);
            } else {
                when = DateUtils.parseStandardDate(input);
            }
            if (when == null) {
                when = LocalDateTime.parse(input.replaceAll(" +", " "),
                        DateTimeFormatter.ofPattern("EEE MMM d HH:mm:ss uuuu", Locale.US)).toInstant(ZoneOffset.UTC);
            }
            Duration remaining = Duration.between(now, when);
            if (remaining.isNegative()) return Duration.ZERO;
            if (remaining.compareTo(policy.maxBackoff()) >= 0) return policy.maxBackoff();
            long millis = remaining.toMillis();
            return Duration.ofMillis(remaining.equals(Duration.ofMillis(millis)) ? millis : millis + 1);
        } catch (DateTimeException | ArithmeticException e) { return null; }
    }
}
