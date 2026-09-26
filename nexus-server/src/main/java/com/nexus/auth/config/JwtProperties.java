package com.nexus.auth.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@ConfigurationProperties(prefix = "nexus.auth.jwt")
@Validated
public record JwtProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTokenTtl,
        @NotBlank String secret
) {
}
