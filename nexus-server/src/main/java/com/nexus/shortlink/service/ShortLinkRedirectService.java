package com.nexus.shortlink.service;

import com.nexus.shortlink.exception.ShortLinkExpiredException;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
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
    private final Clock clock;

    public URI resolveTarget(String shortCode) {
        if (shortCode == null || shortCode.isBlank()) {
            throw new ShortLinkNotFoundException();
        }
        var target = shortLinkMapper.findActiveRedirectTarget(shortCode);
        if (target == null) {
            throw new ShortLinkNotFoundException();
        }
        if (!LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).isBefore(target.expiresAt())) {
            throw new ShortLinkExpiredException();
        }
        return URI.create(target.originalUrl());
    }
}
