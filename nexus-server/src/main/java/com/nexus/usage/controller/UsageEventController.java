package com.nexus.usage.controller;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.usage.dto.TimeRange;
import com.nexus.usage.dto.UsageQueryRequest;
import com.nexus.usage.dto.UsageQueryResponse;
import com.nexus.usage.dto.UsageEventPageResponse;
import com.nexus.usage.service.UsageQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/usage")
public class UsageEventController {
    private final UsageQueryService usageQueryService;
    private final BearerUserIdResolver bearerUserIdResolver;

    @GetMapping("/events")
    public ApiResponse<UsageEventPageResponse> queryMyUsageEvents(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam Long apiKeyId,
            @RequestParam Long applicationId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size) {
        Long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "查询调用记录成功",
                usageQueryService.queryMyUsageEvents(userId, applicationId, apiKeyId, current, size));
    }

    @GetMapping
    public ApiResponse<UsageQueryResponse> queryMyUsageEvent(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam Long apiKeyId,
            @RequestParam Long applicationId,
            @RequestParam TimeRange timeRange,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime customStartTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime customEndTime
            ){
        //验证jwt，获取userid
        Long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>(
                "SUCCESS",
                "查询usage成功",
                usageQueryService.queryMyUsageEvent(userId,
                        new UsageQueryRequest(apiKeyId, applicationId, timeRange, customStartTime, customEndTime))
        );
    }
}
