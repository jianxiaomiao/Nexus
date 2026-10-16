package com.nexus.quota.controller;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.quota.dto.QueryQuotaRequest;
import com.nexus.quota.dto.QueryQuotaResponse;
import com.nexus.quota.service.ApplicationQuotaService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/application")
public class ApplicationQuotaController {
    private final BearerUserIdResolver bearerUserIdResolver;
    private final ApplicationQuotaService quotaService;

    @GetMapping("/{applicationId}/quota")
    public ApiResponse<QueryQuotaResponse> queryMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long applicationId) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "配额查询成功",
                quotaService.queryMyApplicationQuota(userId, new QueryQuotaRequest(applicationId)));
    }
}
