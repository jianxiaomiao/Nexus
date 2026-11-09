package com.nexus.webhook.exception;

public class WebhookSecretRotationConflictException extends RuntimeException {
    public WebhookSecretRotationConflictException() { super("Webhook Secret 已被其他请求轮换"); }
}
