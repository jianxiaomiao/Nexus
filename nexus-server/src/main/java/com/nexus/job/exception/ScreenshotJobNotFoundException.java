package com.nexus.job.exception;

public class ScreenshotJobNotFoundException extends RuntimeException {
    public ScreenshotJobNotFoundException() {
        super("截图任务不存在");
    }
}
