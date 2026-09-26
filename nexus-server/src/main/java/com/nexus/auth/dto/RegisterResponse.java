package com.nexus.auth.dto;

public record RegisterResponse(
        String email,
        String displayName
) {
}