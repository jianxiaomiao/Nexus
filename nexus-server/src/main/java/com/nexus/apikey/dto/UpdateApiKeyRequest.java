package com.nexus.apikey.dto;

import jakarta.validation.constraints.*;

public record UpdateApiKeyRequest(
        @Size(max = 64, message = "密钥名称不能超过64个字符")
        String name,
        @NotNull(message = "应用ID不能为空")
        @Min(value = 1, message = "应用ID必须大于等于1")
        Long applicationId,
        @NotNull(message = "密钥id不能为空")
        @Min(value = 1, message = "密钥id必须大于等于1")
        Long apiKeyId,
        @Min(0)
        @Max(1)
        Integer status
) {
}
