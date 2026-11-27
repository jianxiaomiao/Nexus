package com.nexus.admin.dto;

import com.nexus.quota.dto.QueryQuotaResponse;

import java.time.Instant;

public record AdminApplicationListItemResponse(
        long id,
        long ownerUserId,
        String ownerEmail,
        String name,
        int status,
        QueryQuotaResponse quota,
        Instant createdAt,
        Instant updatedAt
) {
}
