package com.nexus.shortlink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

public record CreateShortLinkRequest(
        @NotBlank @Size(max = 64) String name,
        @NotBlank @Size(max = 2048) String originalUrl,
        @NotNull OffsetDateTime expiresAt
) {
}
