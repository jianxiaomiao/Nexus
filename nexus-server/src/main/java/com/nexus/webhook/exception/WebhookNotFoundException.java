package com.nexus.webhook.exception;

public class WebhookNotFoundException extends RuntimeException {
    public WebhookNotFoundException() { super("Webhook 配置不存在"); }
}
