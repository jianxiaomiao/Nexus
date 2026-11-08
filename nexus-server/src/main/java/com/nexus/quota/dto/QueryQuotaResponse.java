package com.nexus.quota.dto;

import java.time.Instant;

public record QueryQuotaResponse (
        Instant periodStart,
        Instant periodEnd,
        Integer quotaLimit,
        Integer usedCount,
        Integer restCount
){
}
