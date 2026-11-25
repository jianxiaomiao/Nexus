package com.nexus.shortlink.service;

import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.application.entity.Application;
import com.nexus.quota.QuotaTestCleanup;
import com.nexus.quota.mapper.ApplicationQuotaMapper;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.openapi.service.ShortLinkOpenApiService;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.exception.ShortLinkNotFoundException;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
class ShortLinkFlowIntegrationTests {
    private static final String TEST_JWT_SECRET = newTestSecret();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
    }

    private static String newTestSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Autowired
    ShortLinkOpenApiService shortLinkOpenApiService;
    @Autowired ShortLinkRedirectService shortLinkRedirectService;
    @Autowired ShortLinkService shortLinkService;
    @Autowired ApiKeyService apiKeyService;
    @Autowired ShortLinkMapper shortLinkMapper;
    @Autowired ApplicationQuotaMapper quotaMapper;
    @Autowired ApiKeyMapper apiKeyMapper;
    @Autowired ApplicationMapper applicationMapper;
    @Autowired UserMapper userMapper;

    @Test
    void createResolveAndParentDeleteUseRealMysqlRows() {
        User owner = new User();
        owner.setEmail("shortlink-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Short Link Test");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        Application app = new Application();
        app.setOwnerUserId(owner.getId());
        app.setName("shortlink-" + UUID.randomUUID());
        applicationMapper.insert(app);

        ApiKey key = new ApiKey();
        key.setApplicationId(app.getId());
        key.setName("shortlink-key");
        key.setPublicId(UUID.randomUUID().toString());
        key.setSecretHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        key.setKeyPreview("nxk_test*****0000");
        apiKeyMapper.insert(key);

        Long shortLinkId = null;
        try {
            String target = "https://www.douyin.com/video/1";
            var created = shortLinkOpenApiService.create(new ApiKeyIdentity(key.getId(), app.getId()),
                    new CreateShortLinkRequest("demo", target,
                            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
            shortLinkId = created.id();

            assertEquals(key.getId(), created.apiKeyId());
            assertEquals(1, shortLinkService.listMyShortLinks(owner.getId(), key.getId(), 1, 10).records().size());
            assertEquals(target, shortLinkRedirectService.resolveTarget(created.shortCode()).toString());

            key.setStatus(1);
            apiKeyMapper.updateById(key);
            assertThrows(ShortLinkNotFoundException.class,
                    () -> shortLinkRedirectService.resolveTarget(created.shortCode()));
            key.setStatus(0);
            apiKeyMapper.updateById(key);

            app.setStatus(1);
            applicationMapper.updateById(app);
            assertThrows(ShortLinkNotFoundException.class,
                    () -> shortLinkRedirectService.resolveTarget(created.shortCode()));
            app.setStatus(0);
            applicationMapper.updateById(app);

            owner.setStatus(1);
            userMapper.updateById(owner);
            assertThrows(ShortLinkNotFoundException.class,
                    () -> shortLinkRedirectService.resolveTarget(created.shortCode()));
            owner.setStatus(0);
            userMapper.updateById(owner);

            var renamed = shortLinkService.updateMyShortLink(owner.getId(), new UpdateShortLinkRequest(created.id(), "renamed", 1, created.updatedAt()));
            org.junit.jupiter.api.Assertions.assertTrue(renamed.updatedAt().isAfter(created.updatedAt()));
            assertThrows(com.nexus.common.web.ResourceChangedException.class, () -> shortLinkService.updateMyShortLink(
                    owner.getId(), new UpdateShortLinkRequest(created.id(), "stale", null, created.updatedAt())));
            assertThrows(ShortLinkNotFoundException.class,
                    () -> shortLinkRedirectService.resolveTarget(created.shortCode()));
            var enabled = shortLinkOpenApiService.update(new ApiKeyIdentity(key.getId(), app.getId()),
                    new UpdateShortLinkRequest(created.id(), null, 0, renamed.updatedAt()));
            org.junit.jupiter.api.Assertions.assertTrue(enabled.updatedAt().isAfter(renamed.updatedAt()));
            assertThrows(com.nexus.common.web.ResourceChangedException.class, () -> shortLinkOpenApiService.update(
                    new ApiKeyIdentity(key.getId(), app.getId()), new UpdateShortLinkRequest(created.id(), "stale machine edit", null, renamed.updatedAt())));
            assertEquals(target, shortLinkRedirectService.resolveTarget(created.shortCode()).toString());

            // Legacy machine callers must advance the compare token even if the clock is behind stored time.
            var future = java.time.LocalDateTime.now(ZoneOffset.UTC).plusMinutes(1).withNano(123_000_000);
            shortLinkMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers.<ShortLink>lambdaUpdate()
                    .eq(ShortLink::getId, created.id()).set(ShortLink::getUpdatedAt, future));
            var legacy = shortLinkOpenApiService.update(new ApiKeyIdentity(key.getId(), app.getId()),
                    new UpdateShortLinkRequest(created.id(), "legacy edit", null));
            org.junit.jupiter.api.Assertions.assertTrue(legacy.updatedAt().isAfter(future.toInstant(ZoneOffset.UTC)));
            assertThrows(com.nexus.common.web.ResourceChangedException.class, () -> shortLinkService.updateMyShortLink(
                    owner.getId(), new UpdateShortLinkRequest(created.id(), "stale after legacy", null, future.toInstant(ZoneOffset.UTC))));

            apiKeyService.deleteMyApiKey(owner.getId(), new DeleteApiKeyRequest(app.getId(), key.getId()));
            ShortLink persisted = shortLinkMapper.selectById(shortLinkId);
            assertEquals(1, persisted.getIsDeleted());
            assertThrows(ShortLinkNotFoundException.class,
                    () -> shortLinkRedirectService.resolveTarget(created.shortCode()));
        } finally {
            if (shortLinkId != null) shortLinkMapper.deleteById(shortLinkId);
            apiKeyMapper.deleteById(key.getId());
            QuotaTestCleanup.deletePeriods(quotaMapper, app.getId());
            applicationMapper.deleteById(app.getId());
            userMapper.deleteById(owner.getId());
        }
    }
}
