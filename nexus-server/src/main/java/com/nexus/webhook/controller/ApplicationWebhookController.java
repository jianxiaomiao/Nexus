package com.nexus.webhook.controller;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.webhook.dto.*;
import com.nexus.webhook.service.ApplicationWebhookService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/application/{applicationId}/webhook")
public class ApplicationWebhookController {
    private final BearerUserIdResolver bearerUserIdResolver;
    private final ApplicationWebhookService webhookService;

    @GetMapping
    public ApiResponse<ApplicationWebhookResponse> queryMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long applicationId) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "Webhook 配置查询成功",
                webhookService.queryMine(userId, applicationId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<CreatedApplicationWebhookResponse> createMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long applicationId,
            @Valid @RequestBody CreateWebhookRequest request) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "Webhook 创建成功",
                webhookService.createMine(userId, applicationId, request));
    }

    @PutMapping
    public ApiResponse<ApplicationWebhookResponse> updateMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long applicationId,
            @Valid @RequestBody UpdateWebhookRequest request) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "Webhook 更新成功",
                webhookService.updateMine(userId, applicationId, request));
    }

    @PostMapping("/rotate-secret")
    public ApiResponse<RotatedWebhookSecretResponse> rotateSecretMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long applicationId,
            @Valid @RequestBody RotateWebhookSecretRequest request) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "Webhook Secret 轮换成功",
                webhookService.rotateSecretMine(userId, applicationId, request));
    }
}
