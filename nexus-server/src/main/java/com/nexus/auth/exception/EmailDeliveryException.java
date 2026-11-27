package com.nexus.auth.exception;

public class EmailDeliveryException extends RuntimeException {
    public EmailDeliveryException(Throwable cause) {
        super("验证码邮件发送失败，请稍后重试", cause);
    }
}
