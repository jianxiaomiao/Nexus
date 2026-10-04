package com.nexus.apikey.flowTest;

import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.dto.UpdateApiKeyRequest;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyManagementFlowIntegrationTests {
    private static final String BASE_PATH = "/api/apiKey";
    private static final String TEST_PASSWORD = "test-api-key-password";
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
    private PasswordEncoder passwordEncoder;

    @Test
    void ownerCanCreateListUpdateAndDeleteKeyWithoutRedisplayingSecret() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner.userId());

        MvcResult creation = mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("initial", applicationId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.data.publicId").isNotEmpty())
                .andExpect(jsonPath("$.data.keyPreview").isNotEmpty())
                .andExpect(jsonPath("$.data.secretHash").doesNotExist())
                .andReturn();
        long keyId = Long.parseLong(objectMapper.readTree(creation.getResponse().getContentAsString())
                .at("/data/id").asText());
        ApiKey persisted = apiKeyMapper.selectById(keyId);
        assertNotNull(persisted);
        assertEquals(64, persisted.getSecretHash().length());
        assertEquals(0, persisted.getIsDeleted());

        mockMvc.perform(get(BASE_PATH + "/{applicationId}", applicationId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyResponseList.length()").value(1))
                .andExpect(jsonPath("$.data.apiKeyResponseList[0].id").value(keyId))
                .andExpect(jsonPath("$.data.apiKeyResponseList[0].keyPreview").isNotEmpty())
                .andExpect(jsonPath("$.data.apiKeyResponseList[0].apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.apiKeyResponseList[0].secretHash").doesNotExist());

        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApiKeyRequest("renamed", applicationId, keyId, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("renamed"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.secretHash").doesNotExist());

        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeleteApiKeyRequest(applicationId, keyId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        ApiKey deleted = apiKeyMapper.selectById(keyId);
        assertEquals(1, deleted.getIsDeleted());
        assertNotNull(deleted.getDeletedAt());
        mockMvc.perform(get(BASE_PATH + "/{applicationId}", applicationId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyResponseList.length()").value(0));
    }

    @Test
    void anotherUserCannotManageOwnersKeys() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        AuthenticatedUser stranger = loginAsNewUser();
        long applicationId = createApplication(owner.userId());
        long strangersApplicationId = createApplication(stranger.userId());
        long keyId = createKey(owner, applicationId, "owned");

        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("foreign", applicationId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mockMvc.perform(get(BASE_PATH + "/{applicationId}", applicationId)
                        .header("Authorization", bearer(stranger)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApiKeyRequest("stolen", applicationId, keyId, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeleteApiKeyRequest(applicationId, keyId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        // 即使把自己应用的 ID 与别人的 Key ID 拼在同一请求中，也不能越权操作。
        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApiKeyRequest("stolen", strangersApplicationId, keyId, 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(stranger))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeleteApiKeyRequest(strangersApplicationId, keyId))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("API_KEY_NOT_FOUND"));

        ApiKey persisted = apiKeyMapper.selectById(keyId);
        assertEquals("owned", persisted.getName());
        assertEquals(0, persisted.getStatus());
        assertEquals(0, persisted.getIsDeleted());
    }

    @Test
    void deletedKeyNameCanBeReusedAndOldKeyRemainsDeleted() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner.userId());
        long firstKeyId = createKey(owner, applicationId, "reusable");

        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("reusable", applicationId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("API_KEY_NAME_ALREADY_EXISTS"));
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new DeleteApiKeyRequest(applicationId, firstKeyId))))
                .andExpect(status().isOk());

        long replacementId = createKey(owner, applicationId, "reusable");
        assertEquals(1, apiKeyMapper.selectById(firstKeyId).getIsDeleted());
        assertEquals(0, apiKeyMapper.selectById(replacementId).getIsDeleted());
    }

    @Test
    void disabledParentBlocksCreationButStillAllowsManagingExistingKey() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner.userId());
        long keyId = createKey(owner, applicationId, "existing");
        Application application = applicationMapper.selectById(applicationId);
        application.setStatus(1);
        applicationMapper.updateById(application);

        mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("new", applicationId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_DISABLED"));
        mockMvc.perform(get(BASE_PATH + "/{applicationId}", applicationId)
                        .header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyResponseList.length()").value(1));
        mockMvc.perform(put(BASE_PATH)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApiKeyRequest(null, applicationId, keyId, 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(1));
        mockMvc.perform(delete(BASE_PATH)
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new DeleteApiKeyRequest(applicationId, keyId))))
                .andExpect(status().isOk());
        assertEquals(1, apiKeyMapper.selectById(keyId).getIsDeleted());
    }

    @Test
    void missingTokenCannotCreateKey() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner.userId());

        mockMvc.perform(post(BASE_PATH + "/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest("key", applicationId))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));
        assertEquals(0L, apiKeyMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<ApiKey>lambdaQuery()
                        .eq(ApiKey::getApplicationId, applicationId)));
    }

    private AuthenticatedUser loginAsNewUser() throws Exception {
        String email = "api-key-flow-" + UUID.randomUUID() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("API Key Flow User");
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        userMapper.insert(user);

        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        String accessToken = objectMapper.readTree(login.getResponse().getContentAsString())
                .at("/data/accessToken").asText();
        return new AuthenticatedUser(user.getId(), accessToken);
    }

    private long createApplication(long ownerUserId) {
        Application application = new Application();
        application.setOwnerUserId(ownerUserId);
        application.setName("api-key-app-" + UUID.randomUUID());
        applicationMapper.insert(application);
        return application.getId();
    }

    private long createKey(AuthenticatedUser owner, long applicationId, String name) throws Exception {
        MvcResult creation = mockMvc.perform(post(BASE_PATH + "/create")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApiKeyRequest(name, applicationId))))
                .andExpect(status().isOk())
                .andReturn();
        return Long.parseLong(objectMapper.readTree(creation.getResponse().getContentAsString())
                .at("/data/id").asText());
    }

    private String bearer(AuthenticatedUser user) {
        return "Bearer " + user.accessToken();
    }

    private record AuthenticatedUser(long userId, String accessToken) {}
}
