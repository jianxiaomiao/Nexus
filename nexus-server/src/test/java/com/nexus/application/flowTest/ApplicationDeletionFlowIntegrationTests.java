package com.nexus.application.flowTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.dto.CreateApiKeyResponse;
import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.apikey.service.ApiKeyService;
import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.application.service.ApplicationService;
import com.nexus.auth.dto.LoginRequest;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplicationDeletionFlowIntegrationTests {

    private static final String TEST_PASSWORD = "test-application-password";
    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ApplicationMapper applicationMapper;

    @Autowired
    private ApiKeyMapper apiKeyMapper;

    @Autowired
    private ApiKeyService apiKeyService;

    @Autowired
    private ApplicationService applicationService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void ownerCanSoftDeleteApplicationAndListNoLongerShowsIt() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "delete-" + UUID.randomUUID());

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("删除成功"));

        Application deleted = applicationMapper.selectById(applicationId);
        assertNotNull(deleted);
        assertEquals(owner.userId(), deleted.getOwnerUserId());
        assertEquals(1, deleted.getIsDeleted());
        assertNotNull(deleted.getDeletedAt());

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records.length()").value(0));
    }

    @Test
    void deletingApplicationSoftDeletesOnlyItsActiveKeys() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "cascade-" + UUID.randomUUID());
        long otherApplicationId = createApplication(owner, "other-" + UUID.randomUUID());
        CreateApiKeyResponse active = apiKeyService.createMyApiKey(owner.userId(),
                new CreateApiKeyRequest("active", applicationId));
        CreateApiKeyResponse previouslyDeleted = apiKeyService.createMyApiKey(owner.userId(),
                new CreateApiKeyRequest("previously-deleted", applicationId));
        CreateApiKeyResponse unrelated = apiKeyService.createMyApiKey(owner.userId(),
                new CreateApiKeyRequest("unrelated", otherApplicationId));
        apiKeyService.deleteMyApiKey(owner.userId(),
                new DeleteApiKeyRequest(applicationId, previouslyDeleted.id()));
        LocalDateTime previousDeletionTime = apiKeyMapper.selectById(previouslyDeleted.id()).getDeletedAt();

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk());

        Application deletedApplication = applicationMapper.selectById(applicationId);
        ApiKey deletedActiveKey = apiKeyMapper.selectById(active.id());
        ApiKey alreadyDeletedKey = apiKeyMapper.selectById(previouslyDeleted.id());
        ApiKey otherKey = apiKeyMapper.selectById(unrelated.id());
        assertEquals(1, deletedApplication.getIsDeleted());
        assertEquals(1, deletedActiveKey.getIsDeleted());
        assertEquals(deletedApplication.getDeletedAt(), deletedActiveKey.getDeletedAt());
        assertEquals(previousDeletionTime, alreadyDeletedKey.getDeletedAt());
        assertEquals(0, otherKey.getIsDeleted());
        assertNull(otherKey.getDeletedAt());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentKeyCreationCannotSurviveApplicationDeletion() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "concurrent-" + UUID.randomUUID());
        CountDownLatch deletedButUncommitted = new CountDownLatch(1);
        CountDownLatch releaseDeletion = new CountDownLatch(1);
        CountDownLatch creationStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);

        try {
            Future<?> deletion = executor.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                applicationService.deleteMyApplication(owner.userId(), applicationId);
                deletedButUncommitted.countDown();
                try {
                    if (!releaseDeletion.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("timed out waiting to commit deletion");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertTrue(deletedButUncommitted.await(10, TimeUnit.SECONDS));

            Future<?> creation = executor.submit(() -> {
                creationStarted.countDown();
                apiKeyService.createMyApiKey(owner.userId(),
                        new CreateApiKeyRequest("racing-key", applicationId));
            });
            assertTrue(creationStarted.await(10, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> creation.get(200, TimeUnit.MILLISECONDS));

            releaseDeletion.countDown();
            deletion.get(10, TimeUnit.SECONDS);
            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> creation.get(10, TimeUnit.SECONDS));
            assertInstanceOf(ApplicationNotFoundException.class, failure.getCause());
            assertEquals(0L, apiKeyMapper.selectCount(Wrappers.<ApiKey>lambdaQuery()
                    .eq(ApiKey::getApplicationId, applicationId)
                    .eq(ApiKey::getIsDeleted, 0)));
        } finally {
            releaseDeletion.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
            apiKeyMapper.delete(Wrappers.<ApiKey>lambdaQuery()
                    .eq(ApiKey::getApplicationId, applicationId));
            applicationMapper.deleteById(applicationId);
            userMapper.deleteById(owner.userId());
        }
    }

    @Test
    void anotherUsersTokenCannotDeleteOwnersApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        AuthenticatedUser anotherUser = loginAsNewUser();
        long applicationId = createApplication(owner, "owned-" + UUID.randomUUID());

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + anotherUser.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(owner.userId(), persisted.getOwnerUserId());
        assertEquals(0, persisted.getIsDeleted());
        assertNull(persisted.getDeletedAt());
    }

    @Test
    void repeatedDeleteReturnsNotFoundWithoutChangingDeletionTime() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "repeat-" + UUID.randomUUID());

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk());
        LocalDateTime firstDeletedAt = applicationMapper.selectById(applicationId).getDeletedAt();
        assertNotNull(firstDeletedAt);

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        assertEquals(firstDeletedAt, applicationMapper.selectById(applicationId).getDeletedAt());
    }

    @Test
    void deletedApplicationNameCanBeReusedBySameOwner() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String name = "reuse-" + UUID.randomUUID();
        long deletedId = createApplication(owner, name);

        mockMvc.perform(delete("/api/application/{id}", deletedId)
                        .header("Authorization", "Bearer " + owner.accessToken()))
                .andExpect(status().isOk());
        long replacementId = createApplication(owner, name);

        Application replacement = applicationMapper.selectById(replacementId);
        assertNotNull(replacement);
        assertEquals(owner.userId(), replacement.getOwnerUserId());
        assertEquals(0, replacement.getIsDeleted());
        assertEquals(name, replacement.getName());
        assertEquals(1, applicationMapper.selectById(deletedId).getIsDeleted());
    }

    @Test
    void tamperedTokenCannotDeleteApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "secure-" + UUID.randomUUID());

        mockMvc.perform(delete("/api/application/{id}", applicationId)
                        .header("Authorization", "Bearer " + tamperSignature(owner.accessToken())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(0, persisted.getIsDeleted());
        assertNull(persisted.getDeletedAt());
    }

    @Test
    void nonPositivePathIdReturnsBadRequestWithoutDeletingOwnedApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "valid-" + UUID.randomUUID());

        for (String path : new String[] {"/api/application/0", "/api/application/-1"}) {
            mockMvc.perform(delete(path)
                            .header("Authorization", "Bearer " + owner.accessToken()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_APPLICATION_ID"));
        }

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(0, persisted.getIsDeleted());
        assertNull(persisted.getDeletedAt());
    }

    private AuthenticatedUser loginAsNewUser() throws Exception {
        String email = "application-delete-" + UUID.randomUUID() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Application Delete Test User");
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        assertEquals(1, userMapper.insert(user));

        MvcResult loginResult = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(loginResult.getResponse().getContentAsString())
                .at("/data/accessToken").asText();
        return new AuthenticatedUser(user.getId(), token);
    }

    private long createApplication(AuthenticatedUser owner, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApplicationRequest(name))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(result.getResponse().getContentAsString())
                .at("/data/id").asText());
    }

    private String tamperSignature(String token) {
        String[] parts = token.split("\\.", -1);
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        return String.join(".", parts);
    }

    private record AuthenticatedUser(long userId, String accessToken) {}
}
