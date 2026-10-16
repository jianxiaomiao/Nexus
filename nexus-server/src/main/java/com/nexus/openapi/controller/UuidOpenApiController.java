package com.nexus.openapi.controller;

import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.dto.UuidResponse;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.usage.ApiCode;
import com.nexus.usage.web.UsageApi;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/v1/utils")
public class UuidOpenApiController {
    private final MachineIdentityResolver machineIdentityResolver;
    private final ApplicationQuotaService quotaService;

    public UuidOpenApiController(MachineIdentityResolver machineIdentityResolver, ApplicationQuotaService quotaService) {
        this.machineIdentityResolver = machineIdentityResolver;
        this.quotaService = quotaService;
    }

    @UsageApi(ApiCode.UUID_GENERATE)
    @GetMapping("/uuid")
    public ApiResponse<UuidResponse> generateUuid(HttpServletRequest request) {
        // 显式要求机器身份，避免绕过 Filter 配置时误执行接口。
        var identity = machineIdentityResolver.require(request);
        quotaService.consumeOnce(identity);
        // UUID 是本次调用的业务结果；内部身份和完整 API Key 均不进入响应。
        return new ApiResponse<>("SUCCESS", "UUID 已生成",
                new UuidResponse(UUID.randomUUID().toString()));
    }
}
