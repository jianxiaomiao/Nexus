package com.nexus.shortlink.controller;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.shortlink.dto.CreateShortLinkRequest;
import com.nexus.shortlink.dto.UpdateShortLinkRequest;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.nexus.usage.UsageTestCleanup;
import com.nexus.usage.mapper.UsageEventMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ShortLinkHttpIntegrationTests {
    private static final String TEST_JWT_SECRET = newTestSecret();

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    private static String newTestSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    @Value("${local.server.port}") int port;
    @Autowired ApiKeyCredentialGenerator credentialGenerator;
    @Autowired JwtTokenService jwtTokenService;
    @Autowired UserMapper userMapper;
    @Autowired ApplicationMapper applicationMapper;
    @Autowired ApiKeyMapper apiKeyMapper;
    @Autowired ShortLinkMapper shortLinkMapper;
    @Autowired UsageEventMapper usageEventMapper;
    @Autowired @Qualifier("usageExecutor") Executor usageExecutor;
    @Autowired ObjectMapper objectMapper;

    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private User owner;
    private Application app;
    private ApiKey key;
    private GeneratedApiKey credential;
    private Long shortLinkId;

    @BeforeEach
    void createCredential() {
        owner = new User();
        owner.setEmail("short-http-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Short Link HTTP Test");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        app = new Application();
        app.setOwnerUserId(owner.getId());
        app.setName("short-http-" + UUID.randomUUID());
        applicationMapper.insert(app);

        credential = credentialGenerator.generate();
        key = new ApiKey();
        key.setApplicationId(app.getId());
        key.setName("short-http-key");
        key.setPublicId(credential.publicId());
        key.setSecretHash(credential.secretHash());
        key.setKeyPreview(credential.keyPreview());
        apiKeyMapper.insert(key);
    }

    @AfterEach
    void removeRows() throws InterruptedException {
        if (shortLinkId != null) shortLinkMapper.deleteById(shortLinkId);
        if (key != null && key.getId() != null) {
            UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, usageEventMapper, key.getId());
            apiKeyMapper.deleteById(key.getId());
        }
        if (app != null && app.getId() != null) applicationMapper.deleteById(app.getId());
        if (owner != null && owner.getId() != null) userMapper.deleteById(owner.getId());
    }

    @Test
    void machineCreatesManagerDisablesAndPublicRedirectNeedsNoCredential() throws Exception {
        String machineAuthorization = "ApiKey " + credential.plaintextKey();
        String managerAuthorization = "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value();
        String target = "https://www.douyin.com/video/1";
        String createBody = objectMapper.writeValueAsString(new CreateShortLinkRequest(
                "demo", target, OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));

        assertEquals(401, send("GET", "/v1/short-links", null, null).statusCode());
        assertEquals(401, send("GET", "/api/short-links?apiKeyId=" + key.getId(), null, null).statusCode());

        HttpResponse<String> created = send("POST", "/v1/short-links", machineAuthorization, createBody);
        assertEquals(200, created.statusCode());
        shortLinkId = objectMapper.readTree(created.body()).at("/data/id").asLong();
        String shortCode = objectMapper.readTree(created.body()).at("/data/shortCode").asText();
        assertEquals(key.getId(), objectMapper.readTree(created.body()).at("/data/apiKeyId").asLong());

        HttpResponse<String> publicRedirect = send("GET", "/s/" + shortCode, null, null);
        assertEquals(302, publicRedirect.statusCode());
        assertEquals(target, publicRedirect.headers().firstValue("Location").orElseThrow());
        assertTrue(publicRedirect.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));

        HttpResponse<String> machineList = send("GET", "/v1/short-links", machineAuthorization, null);
        assertEquals(200, machineList.statusCode());
        assertEquals(shortLinkId.longValue(), objectMapper.readTree(machineList.body()).at("/data/0/id").asLong());
        HttpResponse<String> managerList = send("GET", "/api/short-links?apiKeyId=" + key.getId(),
                managerAuthorization, null);
        assertEquals(200, managerList.statusCode());
        assertEquals(shortLinkId.longValue(), objectMapper.readTree(managerList.body()).at("/data/0/id").asLong());

        String disableBody = objectMapper.writeValueAsString(new UpdateShortLinkRequest(shortLinkId, null, 1));
        assertEquals(200, send("PUT", "/api/short-links", managerAuthorization, disableBody).statusCode());
        assertEquals(404, send("GET", "/s/" + shortCode, null, null).statusCode());

        String enableBody = objectMapper.writeValueAsString(new UpdateShortLinkRequest(shortLinkId, null, 0));
        assertEquals(200, send("PUT", "/api/short-links", managerAuthorization, enableBody).statusCode());
        assertEquals(302, send("GET", "/s/" + shortCode, null, null).statusCode());

        ShortLink expired = shortLinkMapper.selectById(shortLinkId);
        expired.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).minusSeconds(1));
        shortLinkMapper.updateById(expired);
        assertEquals(410, send("GET", "/s/" + shortCode, null, null).statusCode());

        assertEquals(200, send("DELETE", "/api/short-links/" + shortLinkId,
                managerAuthorization, null).statusCode());
        assertEquals(404, send("GET", "/s/" + shortCode, null, null).statusCode());
    }

    @Test
    void anotherKeyAndAnotherUserCannotManageTheLink() throws Exception {
        String createBody = objectMapper.writeValueAsString(new CreateShortLinkRequest(
                "isolated", "https://www.douyin.com/video/1",
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5)));
        HttpResponse<String> created = send("POST", "/v1/short-links",
                "ApiKey " + credential.plaintextKey(), createBody);
        assertEquals(200, created.statusCode());
        shortLinkId = objectMapper.readTree(created.body()).at("/data/id").asLong();

        GeneratedApiKey otherCredential = credentialGenerator.generate();
        ApiKey otherKey = new ApiKey();
        otherKey.setApplicationId(app.getId());
        otherKey.setName("other-key");
        otherKey.setPublicId(otherCredential.publicId());
        otherKey.setSecretHash(otherCredential.secretHash());
        otherKey.setKeyPreview(otherCredential.keyPreview());
        apiKeyMapper.insert(otherKey);

        User otherUser = new User();
        otherUser.setEmail("short-http-other-" + UUID.randomUUID() + "@example.com");
        otherUser.setDisplayName("Other user");
        otherUser.setPasswordHash("test-only-password-hash");
        userMapper.insert(otherUser);
        try {
            String otherKeyHeader = "ApiKey " + otherCredential.plaintextKey();
            HttpResponse<String> list = send("GET", "/v1/short-links", otherKeyHeader, null);
            assertEquals(200, list.statusCode());
            assertEquals(0, objectMapper.readTree(list.body()).at("/data").size());

            String updateBody = objectMapper.writeValueAsString(
                    new UpdateShortLinkRequest(shortLinkId, "stolen", 1));
            assertEquals(404, send("PUT", "/v1/short-links", otherKeyHeader, updateBody).statusCode());
            assertEquals(404, send("DELETE", "/v1/short-links/" + shortLinkId,
                    otherKeyHeader, null).statusCode());

            String otherUserHeader = "Bearer " + jwtTokenService.issueAccessToken(otherUser.getId()).value();
            assertEquals(404, send("GET", "/api/short-links?apiKeyId=" + key.getId(),
                    otherUserHeader, null).statusCode());
        } finally {
            UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, usageEventMapper, otherKey.getId());
            apiKeyMapper.deleteById(otherKey.getId());
            userMapper.deleteById(otherUser.getId());
        }
    }

    private HttpResponse<String> send(String method, String path, String authorization, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path));
        if (authorization != null) builder.header("Authorization", authorization);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null
                ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
