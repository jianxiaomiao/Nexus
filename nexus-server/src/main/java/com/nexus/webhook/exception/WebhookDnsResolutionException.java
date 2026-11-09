package com.nexus.webhook.exception;

public class WebhookDnsResolutionException extends RuntimeException {
    public WebhookDnsResolutionException(Throwable cause) { super("Webhook 回调域名暂时无法解析", cause); }
}
