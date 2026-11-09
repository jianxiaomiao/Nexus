package com.nexus.webhook.dto;

public record RotatedWebhookSecretResponse(ApplicationWebhookResponse webhook, String secret) {
}
