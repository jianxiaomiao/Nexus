package com.nexus.shortlink.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.exception.ShortLinkExpiredException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/** 公开跳转：不读取访问者身份，只检查短链及父级当前状态。 */
@Service
@RequiredArgsConstructor
public class ShortLinkRedirectService {
    private final ShortLinkMapper shortLinkMapper;
    private final ApiKeyMapper apiKeyMapper;
    private final ApplicationMapper applicationMapper;
    private final UserMapper userMapper;
    private final Clock clock;

    public URI resolveTarget(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new ShortLinkNotFoundException();
        }
        ShortLink link = shortLinkMapper.selectOne(Wrappers.<ShortLink>lambdaQuery()
                .eq(ShortLink::getShortCode, shortCode));
        if (link == null || Integer.valueOf(1).equals(link.getIsDeleted())
                || Integer.valueOf(1).equals(link.getStatus())) {
            throw new ShortLinkNotFoundException();
        }
        ApiKey key = apiKeyMapper.selectById(link.getApiKeyId());
        if (key == null || Integer.valueOf(1).equals(key.getIsDeleted())
                || Integer.valueOf(1).equals(key.getStatus())) {
            throw new ShortLinkNotFoundException();
        }
        Application app = applicationMapper.selectById(key.getApplicationId());
        if (app == null || Integer.valueOf(1).equals(app.getIsDeleted())
                || Integer.valueOf(1).equals(app.getStatus())) {
            throw new ShortLinkNotFoundException();
        }
        User owner = userMapper.selectById(app.getOwnerUserId());
        if (owner == null || Integer.valueOf(1).equals(owner.getIsDeleted())
                || Integer.valueOf(1).equals(owner.getStatus())) {
            throw new ShortLinkNotFoundException();
        }
        if (!LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).isBefore(link.getExpiresAt())) {
            throw new ShortLinkExpiredException();
        }
        return URI.create(link.getOriginalUrl());
    }
}
