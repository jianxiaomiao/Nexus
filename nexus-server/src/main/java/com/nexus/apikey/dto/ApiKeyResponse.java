package com.nexus.apikey.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;

import java.time.LocalDateTime;

public record ApiKeyResponse(
    Long id,
    Long applicationId,
    String name,
    String publicId,
    String keyPreview,
    Integer status,
    LocalDateTime createdAt,
    LocalDateTime updatedAt
) {
}
