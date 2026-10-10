package com.nexus.apikey.dto;

import java.time.LocalDateTime;

public record RotateApiKeyResponse(
        Long id,
        Long applicationId,
        String name,
        String publicId,
        String keyPreview,
        String apiKey,
        Integer status,
        LocalDateTime updatedAt
) {
    @Override
    public String toString() {
        return "RotateApiKeyResponse[id=" + id + ", applicationId=" + applicationId
                + ", name=" + name + ", publicId=" + publicId
                + ", keyPreview=" + keyPreview + ", status=" + status
                + ", updatedAt=" + updatedAt + "]";
    }
}
