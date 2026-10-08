package com.nexus.usage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

public record UsageQueryRequest(
        @NotNull(message = "apiKeyId不能为空")
        Long apiKeyId,
        @NotNull(message = "applicationId不能为空")
        Long applicationId,
        @NotNull(message = "时间区间长度不能为空")
        TimeRange timeRange,
        // 自定义时间场景：timeRange=CUSTOM时，前端额外传这两个参数
        LocalDateTime customStartTime,
        LocalDateTime customEndTime
) {
}
