package com.nexus.webhook.dispatch;

public record WebhookFailure(FailureCategory category, String failureCode) {}
