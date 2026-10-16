package com.nexus.quota.dto;

import jakarta.validation.constraints.NotNull;

public record QueryQuotaRequest(
        @NotNull(message = "应用id不能为空")
        Long applicationId
) {
}
