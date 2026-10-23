package com.nexus.job.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "nexus.screenshot-jobs")
public record ScreenshotJobProperties(String resultDirectory) {
    public ScreenshotJobProperties {
        if (resultDirectory == null || resultDirectory.isBlank()) {
            throw new IllegalArgumentException("nexus.screenshot-jobs.result-directory must not be blank");
        }
    }
}
