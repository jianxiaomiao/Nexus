package com.nexus.application.dto;

import com.nexus.quota.dto.QueryQuotaResponse;

import java.time.Instant;

public record ApplicationListItemResponse(
        Long id,
        String name,
        Integer status,
        Instant createdAt,
        Instant updatedAt,
        QueryQuotaResponse quota
) {
}
