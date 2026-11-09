package com.nexus.webhook.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record RotateWebhookSecretRequest(
        @NotNull(message = "当前 Secret 版本不能为空")
        @Positive(message = "当前 Secret 版本必须为正数")
        Long currentSecretVersion) {
}
