package com.nexus.shortlink.controller;

import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.shortlink.dto.ShortLinkResponse;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import com.nexus.shortlink.service.ShortLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/short-links")
public class ShortLinkController {
    private final BearerUserIdResolver userIdResolver;
    private final ShortLinkService shortLinkService;

    @GetMapping
    public ApiResponse<List<ShortLinkResponse>> listMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam Long apiKeyId) {
        long userId = userIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "查询成功",
                shortLinkService.listMyShortLinks(userId, apiKeyId));
    }

    @PutMapping
    public ApiResponse<ShortLinkResponse> updateMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UpdateShortLinkRequest body) {
        long userId = userIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "更新成功",
                shortLinkService.updateMyShortLink(userId, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id) {
        long userId = userIdResolver.resolve(authorization);
        shortLinkService.deleteMyShortLink(userId, id);
        return new ApiResponse<>("SUCCESS", "删除成功", null);
    }
}
