package com.nexus.job.dto;

import com.nexus.job.entity.ScreenshotJob;
import com.nexus.job.model.ScreenshotJobStatus;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public record CreateScreenshotJobResponse(
        String jobId,
        ScreenshotJobStatus status,
        int attemptCount,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public static CreateScreenshotJobResponse from(ScreenshotJob job) {
        return new CreateScreenshotJobResponse(
                job.getJobId(),
                ScreenshotJobStatus.fromCode(job.getStatus()),
                job.getAttemptCount(),
                job.getCreatedAt().atOffset(ZoneOffset.UTC),
                job.getUpdatedAt().atOffset(ZoneOffset.UTC)
        );
    }
}
