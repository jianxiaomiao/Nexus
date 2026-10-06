package com.nexus.shortlink.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortLinkRedirectServiceTests {
    @Mock ShortLinkMapper shortLinkMapper;
    @Mock ApiKeyMapper apiKeyMapper;
    @Mock ApplicationMapper applicationMapper;
    @Mock UserMapper userMapper;
    @Mock Clock clock;
    @InjectMocks ShortLinkRedirectService service;

    @BeforeAll
    static void initializeMappings() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ShortLinkMapper.class.getName());
        assistant.setCurrentNamespace(ShortLinkMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ShortLink.class);
    }

    @Test
    void expiresExactlyAtExpiresAt() {
        ShortLink link = activeLink();
        prepareParent(link);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        assertThrows(ShortLinkExpiredException.class, () -> service.resolveTarget("FreshCode123"));
    }

    @Test
    void disabledParentHidesLinkBeforeExpiryCheck() {
        ShortLink link = activeLink();
        when(shortLinkMapper.selectOne(any())).thenReturn(link);
        ApiKey key = new ApiKey();
        key.setStatus(1);
        when(apiKeyMapper.selectById(7L)).thenReturn(key);
        assertThrows(ShortLinkNotFoundException.class, () -> service.resolveTarget("FreshCode123"));
        verifyNoInteractions(applicationMapper, userMapper, clock);
    }

    private ShortLink activeLink() {
        ShortLink link = new ShortLink();
        link.setApiKeyId(7L);
        link.setOriginalUrl("https://www.douyin.com/video/1");
        link.setExpiresAt(LocalDateTime.parse("2026-10-06T00:00:00"));
        return link;
    }

    private void prepareParent(ShortLink link) {
        when(shortLinkMapper.selectOne(any())).thenReturn(link);
        ApiKey key = new ApiKey();
        key.setApplicationId(3L);
        when(apiKeyMapper.selectById(7L)).thenReturn(key);
        Application app = new Application();
        app.setOwnerUserId(9L);
        when(applicationMapper.selectById(3L)).thenReturn(app);
        when(userMapper.selectById(9L)).thenReturn(new User());
    }
}
