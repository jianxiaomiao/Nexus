package com.nexus.job.exception;

public class ScreenshotSubmissionException extends RuntimeException {
    public enum Kind { INVALID, CONFLICT, EXPIRED }
    private final Kind kind;
    public ScreenshotSubmissionException(Kind kind) { super("截图提交标识无效、已过期或与原请求不一致"); this.kind = kind; }
    public Kind kind() { return kind; }
}
