package com.nexus.webhook.dto;

import jakarta.validation.constraints.Size;

public record UpdateWebhookRequest(
        @Size(max = 2048, message = "回调 URL 不能超过 2048 个字符") String url,
        Integer status) {
}
