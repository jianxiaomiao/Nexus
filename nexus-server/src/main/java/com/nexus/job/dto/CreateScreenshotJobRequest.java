package com.nexus.job.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateScreenshotJobRequest(
        @NotBlank(message = "目标 URL 不能为空")
        @Size(max = 2048, message = "目标 URL 不能超过 2048 个字符")
        String url
) {
}
