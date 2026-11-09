package com.nexus.webhook.exception;

public class WebhookAlreadyExistsException extends RuntimeException {
    public WebhookAlreadyExistsException(Throwable cause) { super("该应用已配置 Webhook", cause); }
}
