package com.nexus.quota.dto;

import java.time.LocalDateTime;

public record ExpandOnceRes(
        LocalDateTime periodStart,
        Integer quotaLimit,
        Integer usedCount,
        Integer restCount
) {
}
