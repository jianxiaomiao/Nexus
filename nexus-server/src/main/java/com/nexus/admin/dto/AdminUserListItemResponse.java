package com.nexus.admin.dto;

import java.time.Instant;

public record AdminUserListItemResponse(
        long id,
        String email,
        String displayName,
        String role,
        int status,
        long applicationCount,
        Instant createdAt
) {
}
