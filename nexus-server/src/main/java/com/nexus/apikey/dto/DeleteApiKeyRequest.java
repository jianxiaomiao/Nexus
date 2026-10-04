package com.nexus.apikey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record DeleteApiKeyRequest(
        @NotNull(message = "应用ID不能为空")
        @Min(value = 1, message = "应用ID必须大于等于1")
        Long applicationId,
        @NotNull(message = "密钥id不能为空")
        @Min(value = 1, message = "密钥id必须大于等于1")
        Long apiKeyId
) {
}
