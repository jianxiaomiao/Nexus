package com.nexus.job.dto;

import com.nexus.job.model.ScreenshotJobStatus;

public record ScreenshotJobNotReadyResponse(ScreenshotJobStatus status) {
}
