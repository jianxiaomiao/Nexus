package com.nexus.usage.dto;

import java.time.OffsetDateTime;
import java.util.List;

public record UsageEventPageResponse(long total, long current, long size, List<Event> records) {
    public record Event(String apiCode, Integer httpStatusCode, Integer durationMs, OffsetDateTime occurredAt) {
    }
}
