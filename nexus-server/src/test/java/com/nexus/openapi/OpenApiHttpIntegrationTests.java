package com.nexus.openapi;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.openapi.dto.HashAlgorithm;
import com.nexus.openapi.dto.HashRequest;
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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiHttpIntegrationTests {
    private static final String UUID_PATH = "/v1/utils/uuid";
    private static final String HASH_PATH = "/v1/utils/hash";
    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(SecureRandom.getSeed(32));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Value("${local.server.port}") private int port;
    @Autowired private ApiKeyCredentialGenerator generator;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private UserMapper userMapper;
    @Autowired private ApplicationMapper applicationMapper;
    @Autowired private ApiKeyMapper apiKeyMapper;
    @Autowired private UsageEventMapper usageEventMapper;
    @Autowired @Qualifier("usageExecutor") private Executor usageExecutor;
    @Autowired private ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private User owner;
    private Application application;
    private ApiKey key;
    private GeneratedApiKey generated;

    @BeforeEach
    void createCredential() {
        owner = new User();
        owner.setEmail("open-api-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Open API integration user");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("open-api-app-" + UUID.randomUUID());
        applicationMapper.insert(application);

        generated = generator.generate();
        key = new ApiKey();
        key.setApplicationId(application.getId());
        key.setName("integration-key");
        key.setPublicId(generated.publicId());
        key.setSecretHash(generated.secretHash());
        key.setKeyPreview(generated.keyPreview());
        apiKeyMapper.insert(key);
    }

    @AfterEach
    void removeCredential() throws InterruptedException {
        if (key != null && key.getId() != null) {
            UsageTestCleanup.deleteEventsAfterPendingWrites(usageExecutor, usageEventMapper, key.getId());
            apiKeyMapper.deleteById(key.getId());
        }
        if (application != null && application.getId() != null) applicationMapper.deleteById(application.getId());
        if (owner != null && owner.getId() != null) userMapper.deleteById(owner.getId());
    }

    @Test
    void validKeyReturnsDifferentUuidsWithoutExposingCredentialOrIdentity() throws Exception {
        HttpResponse<String> first = get(UUID_PATH, apiKeyHeader());
        HttpResponse<String> second = get(UUID_PATH, apiKeyHeader());

        assertEquals(200, first.statusCode());
        assertEquals(200, second.statusCode());
        assertEquals(200, get(UUID_PATH, "apikey " + generated.plaintextKey()).statusCode());
        assertEquals("SUCCESS", objectMapper.readTree(first.body()).at("/code").asText());
        String firstUuid = objectMapper.readTree(first.body()).at("/data/uuid").asText();
        String secondUuid = objectMapper.readTree(second.body()).at("/data/uuid").asText();
        assertEquals(firstUuid, UUID.fromString(firstUuid).toString());
        assertNotEquals(firstUuid, secondUuid);
        assertFalse(first.body().contains(generated.plaintextKey()));
        assertFalse(first.body().contains(generated.secretHash()));
        assertFalse(first.body().contains("apiKeyId"));
        assertFalse(first.body().contains("applicationId"));
    }

    @Test
    void hashReturnsKnownSha256DigestOverHttp() throws Exception {
        String body = objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA256, "hello"));
        HttpResponse<String> response = postHash(body, apiKeyHeader());

        assertEquals(200, response.statusCode());
        assertEquals("SUCCESS", objectMapper.readTree(response.body()).at("/code").asText());
        assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
                objectMapper.readTree(response.body()).at("/data/hashContent").asText());
    }

    @Test
    void hashReturnsKnownSha512DigestOverHttp() throws Exception {
        String body = objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA512, "abc"));

        HttpResponse<String> response = postHash(body, apiKeyHeader());

        assertEquals(200, response.statusCode());
        assertEquals("SUCCESS", objectMapper.readTree(response.body()).at("/code").asText());
        assertEquals("ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39"
                        + "a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
                objectMapper.readTree(response.body()).at("/data/hashContent").asText());
    }

    @Test
    void hashAcceptsEmptyStringOverHttp() throws Exception {
        String body = objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA256, ""));

        HttpResponse<String> response = postHash(body, apiKeyHeader());

        assertEquals(200, response.statusCode());
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                objectMapper.readTree(response.body()).at("/data/hashContent").asText());
    }

    @Test
    void hashRejectsMissingFieldsWithValidationErrors() throws Exception {
        HttpResponse<String> missingAlgorithm = postHash("{\"value\":\"hello\"}", apiKeyHeader());
        HttpResponse<String> missingValue = postHash("{\"algorithm\":\"SHA256\"}", apiKeyHeader());

        assertEquals(400, missingAlgorithm.statusCode());
        assertEquals("VALIDATION_ERROR", objectMapper.readTree(missingAlgorithm.body()).at("/code").asText());
        assertEquals("算法为空", objectMapper.readTree(missingAlgorithm.body()).at("/data/algorithm").asText());
        assertEquals(400, missingValue.statusCode());
        assertEquals("VALIDATION_ERROR", objectMapper.readTree(missingValue.body()).at("/code").asText());
        assertEquals("输入内容为空", objectMapper.readTree(missingValue.body()).at("/data/value").asText());
    }

    @Test
    void hashRejectsUnknownAlgorithmWithConsistentBadRequest() throws Exception {
        HttpResponse<String> response = postHash("{\"algorithm\":\"MD5\",\"value\":\"hello\"}", apiKeyHeader());

        assertEquals(400, response.statusCode());
        assertEquals("INVALID_REQUEST_BODY", objectMapper.readTree(response.body()).at("/code").asText());
        assertFalse(response.body().contains("MD5"));
    }

    @Test
    void hashUsesUtf8ByteBoundaryOverHttp() throws Exception {
        String atLimit = "中".repeat(1365) + "a"; // 1365 * 3 + 1 = 4096 bytes
        String overLimit = atLimit + "b";          // 4097 bytes

        HttpResponse<String> accepted = postHash(
                objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA256, atLimit)), apiKeyHeader());
        HttpResponse<String> rejected = postHash(
                objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA256, overLimit)), apiKeyHeader());
        HttpResponse<String> rejectedAscii = postHash(
                objectMapper.writeValueAsString(new HashRequest(HashAlgorithm.SHA256, "a".repeat(4097))), apiKeyHeader());

        assertEquals(200, accepted.statusCode());
        assertEquals(64, objectMapper.readTree(accepted.body()).at("/data/hashContent").asText().length());
        assertEquals(400, rejected.statusCode());
        assertEquals("HASH_INPUT_TOO_LARGE", objectMapper.readTree(rejected.body()).at("/code").asText());
        assertEquals(400, rejectedAscii.statusCode());
        assertEquals("HASH_INPUT_TOO_LARGE", objectMapper.readTree(rejectedAscii.body()).at("/code").asText());
    }

    @Test
    void invalidAndDeletedCredentialsReturn401() throws Exception {
        assertUnauthorized(get(UUID_PATH, null));
        assertUnauthorized(get(UUID_PATH, "ApiKey "));
        assertUnauthorized(get(UUID_PATH, "ApiKey  " + generated.plaintextKey()));
        assertUnauthorized(get(UUID_PATH, "ApiKey malformed"));
        assertUnauthorized(get(UUID_PATH, "ApiKey " + generator.generate().plaintextKey()));

        String fullKey = generated.plaintextKey();
        String wrongSecret = fullKey.substring(0, fullKey.length() - 1)
                + (fullKey.endsWith("A") ? "B" : "A");
        assertUnauthorized(get(UUID_PATH, "ApiKey " + wrongSecret));

        key.setIsDeleted(1);
        key.setDeletedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        assertUnauthorized(get(UUID_PATH, apiKeyHeader()));
    }

    @Test
    void disabledKeyOrParentReturns403AndParentReenableRestoresAccess() throws Exception {
        key.setStatus(1);
        apiKeyMapper.updateById(key);
        assertForbidden(get(UUID_PATH, apiKeyHeader()));

        // Even for a disabled key, a wrong Secret must not reveal the disabled state.
        String fullKey = generated.plaintextKey();
        String wrongSecret = fullKey.substring(0, fullKey.length() - 1)
                + (fullKey.endsWith("A") ? "B" : "A");
        assertUnauthorized(get(UUID_PATH, "ApiKey " + wrongSecret));
        key.setStatus(0);
        apiKeyMapper.updateById(key);

        application.setStatus(1);
        applicationMapper.updateById(application);
        assertForbidden(get(UUID_PATH, apiKeyHeader()));
        application.setStatus(0);
        applicationMapper.updateById(application);
        assertEquals(200, get(UUID_PATH, apiKeyHeader()).statusCode());

        application.setIsDeleted(1);
        application.setDeletedAt(LocalDateTime.now());
        applicationMapper.updateById(application);
        assertUnauthorized(get(UUID_PATH, apiKeyHeader()));
    }

    @Test
    void machineKeyAndHumanJwtCannotBeExchanged() throws Exception {
        String bearer = "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value();
        assertUnauthorized(get(UUID_PATH, bearer));
        HttpResponse<String> managementWithKey = get("/api/application", apiKeyHeader());
        assertEquals(401, managementWithKey.statusCode());
        assertEquals("INVALID_ACCESS_TOKEN",
                objectMapper.readTree(managementWithKey.body()).at("/code").asText());
        assertEquals(200, get("/api/application", bearer).statusCode());
        assertEquals(200, get(UUID_PATH, apiKeyHeader()).statusCode());
    }

    @Test
    void accountStateImmediatelyAffectsExistingJwtAndApiKey() throws Exception {
        String bearer = "Bearer " + jwtTokenService.issueAccessToken(owner.getId()).value();
        assertEquals(200, get("/api/application", bearer).statusCode());
        assertEquals(200, get(UUID_PATH, apiKeyHeader()).statusCode());

        owner.setStatus(1);
        userMapper.updateById(owner);
        assertForbidden(get(UUID_PATH, apiKeyHeader()));
        HttpResponse<String> bannedManagement = get("/api/application", bearer);
        assertEquals(403, bannedManagement.statusCode());
        assertEquals("AUTH_ACCOUNT_FORBIDDEN",
                objectMapper.readTree(bannedManagement.body()).at("/code").asText());
        HttpResponse<String> bannedKeyCreation = createKey(bearer);
        assertEquals(403, bannedKeyCreation.statusCode());
        assertEquals("AUTH_ACCOUNT_FORBIDDEN",
                objectMapper.readTree(bannedKeyCreation.body()).at("/code").asText());

        String fullKey = generated.plaintextKey();
        String wrongSecret = fullKey.substring(0, fullKey.length() - 1)
                + (fullKey.endsWith("A") ? "B" : "A");
        assertUnauthorized(get(UUID_PATH, "ApiKey " + wrongSecret));

        owner.setStatus(0);
        userMapper.updateById(owner);
        assertEquals(200, get("/api/application", bearer).statusCode());
        assertEquals(200, get(UUID_PATH, apiKeyHeader()).statusCode());

        owner.setIsDeleted(1);
        owner.setDeletedAt(LocalDateTime.now());
        userMapper.updateById(owner);
        assertUnauthorized(get(UUID_PATH, apiKeyHeader()));
        HttpResponse<String> deletedManagement = get("/api/application", bearer);
        assertEquals(401, deletedManagement.statusCode());
        assertEquals("INVALID_ACCESS_TOKEN",
                objectMapper.readTree(deletedManagement.body()).at("/code").asText());
        assertEquals(401, createKey(bearer).statusCode());

        key.setStatus(1);
        apiKeyMapper.updateById(key);
        assertUnauthorized(get(UUID_PATH, apiKeyHeader()));
    }

    private String apiKeyHeader() {
        return "ApiKey " + generated.plaintextKey();
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) builder.header("Authorization", authorization);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postHash(String body, String authorization) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + HASH_PATH))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) builder.header("Authorization", authorization);
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> createKey(String bearer) throws Exception {
        String body = objectMapper.writeValueAsString(
                new CreateApiKeyRequest("blocked-by-account-state", application.getId()));
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/apiKey/create"))
                .header("Authorization", bearer)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private void assertUnauthorized(HttpResponse<String> response) throws Exception {
        assertEquals(401, response.statusCode());
        assertEquals("ApiKey realm=\"nexus-openapi\"",
                response.headers().firstValue("WWW-Authenticate").orElse(null));
        assertEquals("INVALID_API_KEY_CREDENTIAL", objectMapper.readTree(response.body()).at("/code").asText());
    }

    private void assertForbidden(HttpResponse<String> response) throws Exception {
        assertEquals(403, response.statusCode());
        assertEquals("API_KEY_FORBIDDEN", objectMapper.readTree(response.body()).at("/code").asText());
        assertTrue(response.headers().firstValue("WWW-Authenticate").isEmpty());
    }
}
