package com.nexus.webhook.exception;

public class InvalidWebhookUrlException extends RuntimeException {
    public InvalidWebhookUrlException() { super("Webhook URL 必须是有效的 HTTPS 公网域名地址"); }
}
