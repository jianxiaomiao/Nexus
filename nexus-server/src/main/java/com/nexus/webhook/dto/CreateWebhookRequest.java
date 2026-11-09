package com.nexus.webhook.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateWebhookRequest(
        @NotBlank(message = "回调 URL 不能为空")
        @Size(max = 2048, message = "回调 URL 不能超过 2048 个字符")
        String url) {
}
