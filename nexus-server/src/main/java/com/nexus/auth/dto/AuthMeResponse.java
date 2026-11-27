package com.nexus.auth.dto;

public record AuthMeResponse(
        long id,
        String email,
        String displayName,
        String role,
        int status
) {
}
