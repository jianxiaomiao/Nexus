package com.nexus.openapi.controller;

import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.service.ShortLinkOpenApiService;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.dto.ShortLinkResponse;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/short-links")
public class ShortLinkOpenApiController {
    private final MachineIdentityResolver identityResolver;
    private final ShortLinkOpenApiService shortLinkService;

    @PostMapping
    public ApiResponse<ShortLinkResponse> create(
            HttpServletRequest request, @Valid @RequestBody CreateShortLinkRequest body) {
        ApiKeyIdentity identity = identityResolver.require(request);
        return new ApiResponse<>("SUCCESS", "短链接创建成功", shortLinkService.create(identity, body));
    }

    @GetMapping
    public ApiResponse<List<ShortLinkResponse>> list(HttpServletRequest request) {
        ApiKeyIdentity identity = identityResolver.require(request);
        return new ApiResponse<>("SUCCESS", "查询成功", shortLinkService.list(identity));
    }

    @PutMapping
    public ApiResponse<ShortLinkResponse> update(
            HttpServletRequest request, @Valid @RequestBody UpdateShortLinkRequest body) {
        ApiKeyIdentity identity = identityResolver.require(request);
        return new ApiResponse<>("SUCCESS", "更新成功", shortLinkService.update(identity, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(HttpServletRequest request, @PathVariable Long id) {
        ApiKeyIdentity identity = identityResolver.require(request);
        shortLinkService.delete(identity, id);
        return new ApiResponse<>("SUCCESS", "删除成功", null);
    }
}
