package com.nexus.shortlink.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.dto.ShortLinkResponse;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.exception.InvalidShortLinkRequestException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** JWT 管理端：只接受已由 BearerUserIdResolver 验证的用户 ID。 */
@Service
@RequiredArgsConstructor
public class ShortLinkService {
    private final ShortLinkMapper shortLinkMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationMapper applicationMapper;
    private final Clock clock;

    public List<ShortLinkResponse> listMyShortLinks(Long userId, Long apiKeyId) {
        requireOwnedKey(userId, apiKeyId);
        return shortLinkMapper.selectList(Wrappers.<ShortLink>lambdaQuery()
                        .eq(ShortLink::getApiKeyId, apiKeyId)
                        .eq(ShortLink::getIsDeleted, 0)
                        .orderByDesc(ShortLink::getCreatedAt)
                        .orderByDesc(ShortLink::getId))
                .stream().map(ShortLinkService::toResponse).toList();
    }

    @Transactional
    public ShortLinkResponse updateMyShortLink(Long userId, UpdateShortLinkRequest request) {
        ShortLink link = requireLink(request == null ? null : request.id());
        requireOwnedKey(userId, link.getApiKeyId());
        if (request.name() == null && request.status() == null) {
            throw new InvalidShortLinkRequestException("至少提供一个要更新的字段");
        }
        String name = request.name() == null ? null : requireName(request.name());
        Integer status = request.status();
        if (status != null && status != 0 && status != 1) {
            throw new InvalidShortLinkRequestException("状态只能是 0 或 1");
        }
        int changed = shortLinkMapper.update(null, Wrappers.<ShortLink>lambdaUpdate()
                .eq(ShortLink::getId, request.id())
                .eq(ShortLink::getApiKeyId, link.getApiKeyId())
                .eq(ShortLink::getIsDeleted, 0)
                .set(name != null, ShortLink::getName, name)
                .set(status != null, ShortLink::getStatus, status)
                .set(ShortLink::getUpdatedAt, utc(clock.instant())));
        if (changed == 0) {
            throw new ShortLinkNotFoundException();
        }
        return toResponse(shortLinkMapper.selectById(request.id()));
    }

    @Transactional
    public void deleteMyShortLink(Long userId, Long shortLinkId) {
        ShortLink link = requireLink(shortLinkId);
        requireOwnedKey(userId, link.getApiKeyId());
        LocalDateTime now = utc(clock.instant());
        int changed = shortLinkMapper.update(null, Wrappers.<ShortLink>lambdaUpdate()
                .eq(ShortLink::getId, shortLinkId)
                .eq(ShortLink::getApiKeyId, link.getApiKeyId())
                .eq(ShortLink::getIsDeleted, 0)
                .set(ShortLink::getIsDeleted, 1)
                .set(ShortLink::getDeletedAt, now)
                .set(ShortLink::getUpdatedAt, now));
        if (changed == 0) {
            throw new ShortLinkNotFoundException();
        }
    }

    private ShortLink requireLink(Long shortLinkId) {
        if (shortLinkId == null || shortLinkId <= 0) {
            throw new InvalidShortLinkRequestException("短链 ID 必须为正数");
        }
        ShortLink link = shortLinkMapper.selectById(shortLinkId);
        if (link == null || Integer.valueOf(1).equals(link.getIsDeleted())) {
            throw new ShortLinkNotFoundException();
        }
        return link;
    }

    private void requireOwnedKey(Long userId, Long apiKeyId) {
        if (userId == null || apiKeyId == null || apiKeyId <= 0) {
            throw new ShortLinkNotFoundException();
        }
        ApiKey key = apiKeyMapper.selectById(apiKeyId);
        if (key == null || Integer.valueOf(1).equals(key.getIsDeleted())) {
            throw new ShortLinkNotFoundException();
        }
        Application app = applicationMapper.selectOne(Wrappers.<Application>lambdaQuery()
                .eq(Application::getId, key.getApplicationId())
                .eq(Application::getOwnerUserId, userId)
                .eq(Application::getIsDeleted, 0));
        if (app == null) {
            throw new ShortLinkNotFoundException();
        }
    }

    private static String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidShortLinkRequestException("短链名称不能为空");
        }
        String trimmed = name.strip();
        if (trimmed.length() > 64) {
            throw new InvalidShortLinkRequestException("短链名称不能超过 64 个字符");
        }
        return trimmed;
    }

    private static LocalDateTime utc(Instant value) {
        return LocalDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant asInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }

    private static ShortLinkResponse toResponse(ShortLink link) {
        return new ShortLinkResponse(link.getId(), link.getApiKeyId(), link.getName(),
                link.getOriginalUrl(), link.getShortCode(), link.getStatus(),
                asInstant(link.getExpiresAt()), asInstant(link.getCreatedAt()), asInstant(link.getUpdatedAt()));
    }
}
