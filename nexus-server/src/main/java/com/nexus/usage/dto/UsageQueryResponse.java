package com.nexus.usage.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record UsageQueryResponse(
        long allTimeCount,
        long periodCount,
        String timeZone,
        OffsetDateTime periodStart,
        OffsetDateTime periodEndExclusive,
        List<DailyCount> dailyCounts,
        List<ApiCount> apiCounts
) {
    public record DailyCount(LocalDate date, long count) {
    }

    public record ApiCount(String apiCode, String apiName, long count) {
    }
}
