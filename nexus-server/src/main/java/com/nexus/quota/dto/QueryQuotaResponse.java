package com.nexus.quota.dto;

import java.time.LocalDateTime;

public record QueryQuotaResponse (
        LocalDateTime periodStart,
        LocalDateTime periodEnd,
        Integer quotaLimit,
        Integer usedCount,
        Integer restCount
){
}
