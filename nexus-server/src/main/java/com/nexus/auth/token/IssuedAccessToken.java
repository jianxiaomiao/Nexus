package com.nexus.auth.token;

public record IssuedAccessToken(
        String value,
        long expiresInSeconds
) {
}
