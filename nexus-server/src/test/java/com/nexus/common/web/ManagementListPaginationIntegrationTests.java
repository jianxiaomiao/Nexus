package com.nexus.common.web;

import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ManagementListPaginationIntegrationTests {
    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(SecureRandom.getSeed(32));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserMapper userMapper;
    @Autowired private ApplicationMapper applicationMapper;
    @Autowired private ApiKeyMapper apiKeyMapper;
    @Autowired private ShortLinkMapper shortLinkMapper;

    @Test
    void elevenRowsSpanTwoPagesAndOwnershipStillApplies() throws Exception {
        User owner = user();
        User other = user();
        List<Application> apps = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            Application app = new Application();
            app.setOwnerUserId(owner.getId());
            app.setName("paged-app-" + UUID.randomUUID());
            applicationMapper.insert(app);
            apps.add(app);
        }
        Application parent = apps.get(0);
        List<ApiKey> keys = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            ApiKey key = new ApiKey();
            key.setApplicationId(parent.getId());
            key.setName("paged-key-" + index);
            key.setPublicId(UUID.randomUUID().toString());
            key.setSecretHash(UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", ""));
            key.setKeyPreview("nxk_test*****1234");
            apiKeyMapper.insert(key);
            keys.add(key);
        }
        ApiKey parentKey = keys.get(0);
        List<ShortLink> links = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            ShortLink link = new ShortLink();
            link.setApiKeyId(parentKey.getId());
            link.setName("paged-link-" + index);
            link.setOriginalUrl("https://example.com/" + index);
            link.setShortCode(UUID.randomUUID().toString().replace("-", "").substring(0, 12));
            link.setExpiresAt(LocalDateTime.now().plusDays(1));
            shortLinkMapper.insert(link);
            links.add(link);
        }
        String ownerBearer = "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value();
        String otherBearer = "Bearer " + jwtTokenService.issueAccessToken(other.getId()).value();

        mockMvc.perform(get("/api/application").header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.records.length()").value(10));
        mockMvc.perform(get("/api/application").param("current", "2").header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(parent.getId()));
        mockMvc.perform(get("/api/application").param("current", "3").param("size", "5")
                        .header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1));
        mockMvc.perform(get("/api/application").param("applicationId", parent.getId().toString())
                        .header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value(parent.getId()));

        String keyPath = "/api/apiKey/" + parent.getId();
        mockMvc.perform(get(keyPath).header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records.length()").value(10))
                .andExpect(jsonPath("$.data.records[0].secretHash").doesNotExist());
        mockMvc.perform(get(keyPath).param("current", "2").header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(parentKey.getId()));
        mockMvc.perform(get(keyPath).param("current", "3").param("size", "5")
                        .header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1));
        mockMvc.perform(get(keyPath).param("apiKeyId", parentKey.getId().toString())
                        .header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records[0].id").value(parentKey.getId()));

        mockMvc.perform(get("/api/short-links").param("apiKeyId", parentKey.getId().toString())
                        .header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(11))
                .andExpect(jsonPath("$.data.records.length()").value(10));
        mockMvc.perform(get("/api/short-links").param("apiKeyId", parentKey.getId().toString())
                        .param("current", "2").header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(links.get(0).getId()));
        mockMvc.perform(get("/api/short-links").param("apiKeyId", parentKey.getId().toString())
                        .param("current", "3").param("size", "5").header("Authorization", ownerBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.records.length()").value(1));

        mockMvc.perform(get("/api/application").header("Authorization", otherBearer))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get(keyPath).header("Authorization", otherBearer)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/short-links").param("apiKeyId", parentKey.getId().toString())
                .header("Authorization", otherBearer)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/application").param("size", "101")
                .header("Authorization", ownerBearer)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_LIST_PAGE"));
        mockMvc.perform(get(keyPath).param("current", "0")
                .header("Authorization", ownerBearer)).andExpect(status().isBadRequest());
    }

    private User user() {
        User user = new User();
        user.setEmail("paged-user-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Paged test user");
        user.setPasswordHash("test-only-password-hash");
        userMapper.insert(user);
        return user;
    }
}
