package com.nexus.webhook.dispatch;

/** The response metadata needed by delivery policy; response bodies are discarded. */
public record WebhookHttpResponse(int statusCode, String retryAfter) {}
