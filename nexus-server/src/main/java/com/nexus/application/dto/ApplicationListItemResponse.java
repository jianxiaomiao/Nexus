package com.nexus.application.dto;

import com.nexus.quota.dto.QueryQuotaResponse;

import java.time.LocalDateTime;

public record ApplicationListItemResponse(
        Long id,
        String name,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        QueryQuotaResponse quota
) {
}
