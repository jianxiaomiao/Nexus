package com.nexus.job.exception;

public class ScreenshotJobFailedException extends RuntimeException {
    private final String failureCode;

    public ScreenshotJobFailedException(String failureCode) {
        super("截图任务执行失败");
        this.failureCode = failureCode;
    }

    public String failureCode() {
        return failureCode;
    }
}
