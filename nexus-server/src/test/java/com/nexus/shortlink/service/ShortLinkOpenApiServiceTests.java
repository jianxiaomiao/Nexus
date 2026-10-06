package com.nexus.shortlink.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.openapi.service.ShortLinkOpenApiService;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShortLinkOpenApiServiceTests {
    @Mock ShortLinkMapper shortLinkMapper;
    @Mock ApiKeyMapper apiKeyMapper;
    @Mock ApplicationMapper applicationMapper;
    @Mock ShortCodeGenerator shortCodeGenerator;
    @Mock ShortLinkTargetPolicy targetPolicy;
    @Mock Clock clock;
    @InjectMocks
    ShortLinkOpenApiService service;

    @BeforeAll
    static void initializeMappings() {
        init(ShortLinkMapper.class, ShortLink.class);
        init(ApiKeyMapper.class, ApiKey.class);
        init(ApplicationMapper.class, Application.class);
    }

    private static void init(Class<?> mapper, Class<?> entity) {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), mapper.getName());
        assistant.setCurrentNamespace(mapper.getName());
        TableInfoHelper.initTableInfo(assistant, entity);
    }

    @Test
    void createUsesAuthenticatedKeyAndRetriesOneCodeCollision() {
        when(clock.instant()).thenReturn(Instant.parse("2026-10-06T00:00:00Z"));
        when(applicationMapper.selectOne(any())).thenReturn(new Application());
        ApiKey key = new ApiKey();
        key.setId(7L);
        key.setApplicationId(3L);
        when(apiKeyMapper.selectOne(any())).thenReturn(key);
        when(shortCodeGenerator.generate()).thenReturn("TakenCode123", "FreshCode123");
        AtomicInteger inserts = new AtomicInteger();
        when(shortLinkMapper.insert(any(ShortLink.class))).thenAnswer(call -> {
            ShortLink link = call.getArgument(0);
            assertEquals(7L, link.getApiKeyId());
            if (inserts.getAndIncrement() == 0) throw new DuplicateKeyException("collision");
            link.setId(15L);
            return 1;
        });

        var result = service.create(new ApiKeyIdentity(7L, 3L),
                new CreateShortLinkRequest("  demo  ", "https://www.douyin.com/video/1",
                        OffsetDateTime.parse("2026-10-06T00:01:00Z")));

        assertEquals(2, inserts.get());
        assertEquals(15L, result.id());
        assertEquals("FreshCode123", result.shortCode());
        assertEquals("demo", result.name());
        verify(targetPolicy).validate("https://www.douyin.com/video/1");
    }
}
