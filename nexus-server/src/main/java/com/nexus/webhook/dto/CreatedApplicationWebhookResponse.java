package com.nexus.webhook.dto;

import java.time.Instant;

public record CreatedApplicationWebhookResponse(
        Long id,
        Long applicationId,
        String url,
        Integer status,
        Long currentSecretVersion,
        Instant createdAt,
        Instant updatedAt,
        String secret) {
}
