package com.nexus.auth.exception;

public class InvalidEmailVerificationCodeException extends RuntimeException {
    public InvalidEmailVerificationCodeException() {
        super("邮箱验证码无效或已过期");
    }
}
