package com.nexus.job.exception;

public class ScreenshotJobCreationException extends RuntimeException {
    public ScreenshotJobCreationException() {
        super("截图任务创建失败");
    }
}
