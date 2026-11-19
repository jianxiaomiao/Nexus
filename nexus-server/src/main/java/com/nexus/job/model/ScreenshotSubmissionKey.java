package com.nexus.job.model;

import com.nexus.job.exception.ScreenshotSubmissionException;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/** Reject expired keys even after metadata cleanup, so a retry cannot silently create another job. */
public record ScreenshotSubmissionKey(String value, Instant expiresAt) {
    public static ScreenshotSubmissionKey parse(String raw, Instant now) {
        if (raw == null) return null;
        try {
            if (!raw.matches("[0-9]{13}\\.[0-9a-f-]{36}")) throw new IllegalArgumentException();
            if (!UUID.fromString(raw.substring(14)).toString().equals(raw.substring(14))) throw new IllegalArgumentException();
            Instant issued = Instant.ofEpochMilli(Long.parseLong(raw.substring(0, 13)));
            if (issued.isAfter(now.plusSeconds(60))) throw new IllegalArgumentException();
            Instant expires = issued.plus(Duration.ofDays(7));
            if (!now.isBefore(expires)) throw new ScreenshotSubmissionException(ScreenshotSubmissionException.Kind.EXPIRED);
            return new ScreenshotSubmissionKey(raw, expires);
        } catch (IllegalArgumentException exception) {
            throw new ScreenshotSubmissionException(ScreenshotSubmissionException.Kind.INVALID);
        }
    }
}
