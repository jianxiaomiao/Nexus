package com.nexus.job.store;

public class ScreenshotResultStoreException extends RuntimeException {
    public ScreenshotResultStoreException() {
        super("截图结果文件存储失败");
    }
}
