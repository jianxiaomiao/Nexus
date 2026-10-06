package com.nexus.shortlink.dto;

import java.time.Instant;

public record ShortLinkResponse(
        Long id,
        Long apiKeyId,
        String name,
        String originalUrl,
        String shortCode,
        Integer status,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
