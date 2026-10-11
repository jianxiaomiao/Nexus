package com.nexus.screenshot.exception;

/** 只使用固定消息，不携带可能含查询参数或凭据的上游异常链。 */
public class ScreenshotException extends RuntimeException {
    public enum Kind {
        INVALID_REQUEST("只接受白名单域名的 HTTPS 页面 URL"),
        PAGE_FETCH_FAILED("目标页面无法获取或不是允许的资源类型"),
        TIMEOUT("截图处理超时"),
        LIMIT_EXCEEDED("页面资源数量或大小超过截图限制"),
        BUSY("截图服务正在处理请求，请稍后重试"),
        ENGINE_UNAVAILABLE("截图浏览器无法启动或运行，请确认 Chromium 安装与运行环境");

        private final String message;
        Kind(String message) { this.message = message; }
    }

    private final Kind kind;
    // 只允许枚举与数值进入诊断日志，不能传入上游消息或 URL。
    public enum DiagnosticReason {
        REQUEST_COUNT, RESOURCE_BYTES, TOTAL_BYTES, PNG_BYTES,
        UPSTREAM_STATUS, RESOURCE_TYPE, DNS_FAILURE, NETWORK_IO
    }
    public record Diagnostic(DiagnosticReason reason, long observed, long limit) { }
    private final Diagnostic diagnostic;

    public ScreenshotException(Kind kind) { this(kind, null, -1, -1); }
    public ScreenshotException(Kind kind, DiagnosticReason reason) { this(kind, reason, -1, -1); }
    public ScreenshotException(Kind kind, DiagnosticReason reason, long observed, long limit) {
        super(kind.message);
        this.kind = kind;
        this.diagnostic = reason == null ? null : new Diagnostic(reason, observed, limit);
    }
    public Kind kind() { return kind; }
    public Diagnostic diagnostic() { return diagnostic; }
    public String code() { return kind == Kind.INVALID_REQUEST ? "INVALID_SCREENSHOT_REQUEST" : "SCREENSHOT_" + kind.name(); }
}
