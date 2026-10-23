package com.nexus.job.exception;

public class ScreenshotJobResultExpiredException extends RuntimeException {
    public ScreenshotJobResultExpiredException() {
        super("截图任务结果已过期");
    }
}
