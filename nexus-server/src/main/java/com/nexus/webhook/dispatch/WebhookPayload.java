package com.nexus.webhook.dispatch;

import com.nexus.webhook.entity.WebhookOutbox;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import tools.jackson.databind.ObjectMapper;

/** Outbox timestamps are stored as UTC; JSON exposes ISO-8601 timestamps with a UTC offset. */
public record WebhookPayload(String eventId, String eventType, Long applicationId, String jobId,
                             OffsetDateTime completedAt, Result result, OffsetDateTime resultExpiresAt,
                             String failureCode) {
    public record Result(Long sizeBytes, String sha256) { }

    public static WebhookPayload from(WebhookOutbox event) {
        Result result = event.getResultSizeBytes() == null && event.getResultSha256() == null ? null
                : new Result(event.getResultSizeBytes(), event.getResultSha256());
        return new WebhookPayload(event.getEventId(), event.getEventType(), event.getApplicationId(), event.getJobId(),
                utc(event.getCompletedAt()), result, utc(event.getResultExpiresAt()), event.getFailureCode());
    }

    private static OffsetDateTime utc(LocalDateTime value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }

    public byte[] toBytes(ObjectMapper mapper) { return mapper.writeValueAsBytes(this); }
}
