package com.nexus.auth.exception;

public class EmailCodeRateLimitedException extends RuntimeException {
    public EmailCodeRateLimitedException() {
        super("验证码发送过于频繁，请 60 秒后再试");
    }

    public EmailCodeRateLimitedException(String message) {
        super(message);
    }
}
