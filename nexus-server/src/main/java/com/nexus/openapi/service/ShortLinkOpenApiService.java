package com.nexus.openapi.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.dto.ShortLinkResponse;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.exception.InvalidShortLinkRequestException;
import com.nexus.shortlink.exception.ShortCodeExhaustedException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.shortlink.service.ShortCodeGenerator;
import com.nexus.shortlink.service.ShortLinkTargetPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;

/** /v1/* 机器端：身份由 MachineApiKeyFilter 提供，客户端不提交归属 ID。 */
@Service
@RequiredArgsConstructor
public class ShortLinkOpenApiService {
    private static final int MAX_COLLISION_RETRIES = 3;

    private final ShortLinkMapper shortLinkMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationMapper applicationMapper;
    private final ShortCodeGenerator shortCodeGenerator;
    private final ShortLinkTargetPolicy targetPolicy;
    private final Clock clock;

    @Transactional
    public ShortLinkResponse create(ApiKeyIdentity identity, CreateShortLinkRequest request) {
        Long keyId = requireKeyId(identity);
        if (identity.applicationId() == null) {
            throw new InvalidApiKeyCredentialException();
        }
        if (request == null || request.expiresAt() == null) {
            throw new InvalidShortLinkRequestException("必须提供到期时间");
        }
        String name = requireName(request.name());
        targetPolicy.validate(request.originalUrl());
        Instant now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        Instant expiresAt = request.expiresAt().toInstant().truncatedTo(ChronoUnit.MILLIS);
        if (!expiresAt.isAfter(now)) {
            throw new InvalidShortLinkRequestException("到期时间必须晚于当前时间");
        }

        // 与父级软删除串行化，固定 Application → Key 的加锁顺序。
        Application app = applicationMapper.selectOne(Wrappers.<Application>lambdaQuery()
                .eq(Application::getId, identity.applicationId()).last("FOR UPDATE"));
        if (app == null || Integer.valueOf(1).equals(app.getIsDeleted())) {
            throw new InvalidApiKeyCredentialException();
        }
        ApiKey key = apiKeyMapper.selectOne(Wrappers.<ApiKey>lambdaQuery()
                .eq(ApiKey::getId, keyId)
                .eq(ApiKey::getApplicationId, identity.applicationId()).last("FOR UPDATE"));
        if (key == null || Integer.valueOf(1).equals(key.getIsDeleted())) {
            throw new InvalidApiKeyCredentialException();
        }
        if (Integer.valueOf(1).equals(app.getStatus()) || Integer.valueOf(1).equals(key.getStatus())) {
            throw new ApiKeyForbiddenException();
        }

        LocalDateTime createdAt = utc(now);
        for (int attempt = 0; attempt <= MAX_COLLISION_RETRIES; attempt++) {
            ShortLink link = new ShortLink();
            link.setApiKeyId(keyId);
            link.setName(name);
            link.setOriginalUrl(request.originalUrl());
            link.setShortCode(shortCodeGenerator.generate());
            link.setStatus(0);
            link.setIsDeleted(0);
            link.setExpiresAt(utc(expiresAt));
            link.setCreatedAt(createdAt);
            link.setUpdatedAt(createdAt);
            try {
                shortLinkMapper.insert(link);
                return toResponse(link);
            } catch (DuplicateKeyException collision) {
                if (attempt == MAX_COLLISION_RETRIES) {
                    throw new ShortCodeExhaustedException();
                }
            }
        }
        throw new ShortCodeExhaustedException();
    }

    public List<ShortLinkResponse> list(ApiKeyIdentity identity) {
        Long keyId = requireKeyId(identity);
        return shortLinkMapper.selectList(Wrappers.<ShortLink>lambdaQuery()
                        .eq(ShortLink::getApiKeyId, keyId)
                        .eq(ShortLink::getIsDeleted, 0)
                        .orderByDesc(ShortLink::getCreatedAt)
                        .orderByDesc(ShortLink::getId))
                .stream().map(ShortLinkOpenApiService::toResponse).toList();
    }

    @Transactional
    public ShortLinkResponse update(ApiKeyIdentity identity, UpdateShortLinkRequest request) {
        Long keyId = requireKeyId(identity);
        if (request == null || request.id() == null || request.id() <= 0) {
            throw new InvalidShortLinkRequestException("短链 ID 必须为正数");
        }
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
                .eq(ShortLink::getApiKeyId, keyId)
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
    public void delete(ApiKeyIdentity identity, Long shortLinkId) {
        Long keyId = requireKeyId(identity);
        if (shortLinkId == null || shortLinkId <= 0) {
            throw new InvalidShortLinkRequestException("短链 ID 必须为正数");
        }
        LocalDateTime now = utc(clock.instant());
        int changed = shortLinkMapper.update(null, Wrappers.<ShortLink>lambdaUpdate()
                .eq(ShortLink::getId, shortLinkId)
                .eq(ShortLink::getApiKeyId, keyId)
                .eq(ShortLink::getIsDeleted, 0)
                .set(ShortLink::getIsDeleted, 1)
                .set(ShortLink::getDeletedAt, now)
                .set(ShortLink::getUpdatedAt, now));
        if (changed == 0) {
            throw new ShortLinkNotFoundException();
        }
    }

    private static Long requireKeyId(ApiKeyIdentity identity) {
        if (identity == null || identity.apiKeyId() == null) {
            throw new InvalidApiKeyCredentialException();
        }
        return identity.apiKeyId();
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
