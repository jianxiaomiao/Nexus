package com.nexus.quota.dto;

import jakarta.validation.constraints.NotNull;

public record ExpendOnceReq(
    @NotNull(message = "应用id不能为空")
    Long applicationId,
    @NotNull(message = "apiKey不能为空")
    Long apiKeyId
) {
}
