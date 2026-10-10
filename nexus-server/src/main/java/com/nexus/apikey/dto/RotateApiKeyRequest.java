package com.nexus.apikey.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RotateApiKeyRequest(
        @NotBlank(message = "当前 publicId 不能为空")
        @Size(max = 36, message = "当前 publicId 长度不能超过36字符")
        String expectedPublicId
) {
}
