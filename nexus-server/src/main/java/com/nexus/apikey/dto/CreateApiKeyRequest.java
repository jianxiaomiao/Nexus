package com.nexus.apikey.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateApiKeyRequest(
        @NotBlank(message = "密钥名称不能为空")
        @Size(max = 64, message = "密钥名称不能超过64个字符")
        String name,
        @NotNull(message = "应用ID不能为空")
        @Min(value = 1, message = "应用ID必须大于等于1")
        Long applicationId
) {
}
