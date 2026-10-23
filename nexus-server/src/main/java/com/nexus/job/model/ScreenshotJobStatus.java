package com.nexus.job.model;

public enum ScreenshotJobStatus {
    QUEUED(0),
    RUNNING(1),
    SUCCEEDED(2),
    FAILED(3),
    EXPIRED(4);

    private final int code;

    ScreenshotJobStatus(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static ScreenshotJobStatus fromCode(Integer code) {
        if (code != null) {
            for (ScreenshotJobStatus status : values()) {
                if (status.code == code) {
                    return status;
                }
            }
        }
        throw new IllegalStateException("Unknown screenshot job status");
    }
}
