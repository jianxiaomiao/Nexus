package com.nexus.apikey.controller;

import com.nexus.apikey.dto.*;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.ListPage;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;


@RestController
@AllArgsConstructor
@RequestMapping("/api/apiKey")
public class ApiKeyController {
    private final ApiKeyService apiKeyService;

    private final BearerUserIdResolver bearerUserIdResolver;

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/create")
    public ApiResponse<CreateApiKeyResponse> createMyApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateApiKeyRequest request
            ){
        //验证jwt，获取userid
        Long userId = bearerUserIdResolver.resolve(authorization);

        CreateApiKeyResponse createApiKeyResponse = apiKeyService.createMyApiKey(userId, request);

        return new ApiResponse<CreateApiKeyResponse>(
                "SUCCESS",
                "创建成功",
                createApiKeyResponse
        );
    }

    @GetMapping("/{applicationId}")
    public ApiResponse<ListPage<ApiKeyResponse>> listMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long applicationId,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Long apiKeyId) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "查询成功",
                apiKeyService.listMyApiKeys(userId, applicationId, current, size, apiKeyId));
    }

    @PutMapping
    public ApiResponse<ApiKeyResponse> updateMyApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UpdateApiKeyRequest request
    ){
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<ApiKeyResponse>("SUCCESS", "更新成功",
                apiKeyService.updateMyApiKey(userId, request));
    }

    @PostMapping("/{apiKeyId}/rotate")
    public ApiResponse<RotateApiKeyResponse> rotateMyApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long apiKeyId,
            @Valid @RequestBody RotateApiKeyRequest request) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "轮换成功",
                apiKeyService.rotateMyApiKey(userId, apiKeyId, request.expectedPublicId()));
    }

    @DeleteMapping
    public ApiResponse<Void> deleteMyApiKey(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody DeleteApiKeyRequest request
    ){
        long userId = bearerUserIdResolver.resolve(authorization);
        apiKeyService.deleteMyApiKey(userId, request);
        return new ApiResponse<Void>(
                "SUCCESS",
                "删除成功",
                null
        );
    }

}
