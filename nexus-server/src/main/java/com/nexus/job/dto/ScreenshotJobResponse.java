package com.nexus.job.dto;

import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.model.ScreenshotJobStatus;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record ScreenshotJobResponse(
        String jobId,
        ScreenshotJobStatus status,
        int attemptCount,
        Long resultSizeBytes,
        String resultSha256,
        Integer blockedResources,
        Boolean resourceLimitReached,
        OffsetDateTime resultExpiresAt,
        String failureCode,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static ScreenshotJobResponse from(ScreenshotJob job) {
        return new ScreenshotJobResponse(
                job.getJobId(),
                ScreenshotJobStatus.fromCode(job.getStatus()),
                job.getAttemptCount(),
                job.getResultSizeBytes(),
                job.getResultSha256(),
                job.getBlockedResources(),
                job.getResourceLimitReached(),
                utc(job.getResultExpiresAt()),
                job.getFailureCode(),
                utc(job.getStartedAt()),
                utc(job.getCompletedAt()),
                utc(job.getCreatedAt()),
                utc(job.getUpdatedAt())
        );
    }

    private static OffsetDateTime utc(LocalDateTime value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }
}
