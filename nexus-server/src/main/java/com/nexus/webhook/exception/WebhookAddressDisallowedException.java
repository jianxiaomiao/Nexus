package com.nexus.webhook.exception;

public class WebhookAddressDisallowedException extends RuntimeException {
    public WebhookAddressDisallowedException(Throwable cause) { super("Webhook 回调地址不允许访问", cause); }
}
