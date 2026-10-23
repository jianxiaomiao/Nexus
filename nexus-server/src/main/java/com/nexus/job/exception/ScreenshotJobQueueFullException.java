package com.nexus.job.exception;

public class ScreenshotJobQueueFullException extends RuntimeException {
    public ScreenshotJobQueueFullException() {
        super("截图任务队列已满，请稍后重试");
    }
}
