package com.nexus.application.dto;

import java.time.Instant;

public record ApplicationResponse (
        Long id,
        String name,
        Integer status,
        Instant createdAt,
        Instant updatedAt
){
}
