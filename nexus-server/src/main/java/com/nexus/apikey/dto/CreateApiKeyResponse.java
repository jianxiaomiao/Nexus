package com.nexus.apikey.dto;

import java.time.LocalDateTime;

public record CreateApiKeyResponse(
        Long id,
        Long applicationId,
        String name,
        String publicId,
        String keyPreview,
        String apiKey,
        Integer status,
        LocalDateTime createdAt
) {
    @Override
    public String toString() {
        return "CreateApiKeyResponse[id=" + id + ", applicationId=" + applicationId
                + ", name=" + name + ", publicId=" + publicId
                + ", keyPreview=" + keyPreview + ", status=" + status
                + ", createdAt=" + createdAt + "]";
    }
}
