package com.nexus.job.exception;

import com.nexus.job.model.ScreenshotJobStatus;

public class ScreenshotJobNotReadyException extends RuntimeException {
    private final ScreenshotJobStatus status;

    public ScreenshotJobNotReadyException(ScreenshotJobStatus status) {
        super("截图任务结果尚未就绪");
        this.status = status;
    }

    public ScreenshotJobStatus status() {
        return status;
    }
}
