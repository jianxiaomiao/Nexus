package com.nexus.application.dto;

import java.time.LocalDateTime;

public record ApplicationResponse (
        Long id,
        String name,
        Integer status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
){
}
