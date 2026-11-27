package com.nexus.auth.exception;

public class InvalidCaptchaException extends RuntimeException {
    public InvalidCaptchaException() {
        super("图形验证码无效或已过期，请刷新重试");
    }
}
