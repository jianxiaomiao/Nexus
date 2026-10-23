package com.nexus.job.exception;

public class ScreenshotJobSubmissionUnavailableException extends RuntimeException {
    public ScreenshotJobSubmissionUnavailableException() {
        super("截图任务提交暂时不可用，请稍后重试");
    }
}
