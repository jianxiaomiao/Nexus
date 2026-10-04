package com.nexus.application.flowTest;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.UpdateApplicationRequest;
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
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplicationUpdateFlowIntegrationTests {

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
    private PasswordEncoder passwordEncoder;

    @Test
    void ownerTokenShouldUpdateApplicationAndReturnPersistedValues() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        long applicationId = createApplication(owner, "original-" + UUID.randomUUID());
        String newName = "renamed-" + UUID.randomUUID();

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(applicationId, "  " + newName + "  ", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("更新成功"))
                .andExpect(jsonPath("$.data.id").value(applicationId))
                .andExpect(jsonPath("$.data.name").value(newName))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.data.updatedAt").isNotEmpty());

        Application persisted = applicationMapper.selectById(applicationId);
        assertNotNull(persisted);
        assertEquals(owner.userId(), persisted.getOwnerUserId());
        assertEquals(newName, persisted.getName());
        assertEquals(1, persisted.getStatus());
        assertEquals(0, persisted.getIsDeleted());
    }

    @Test
    void anotherUsersTokenShouldNotUpdateOwnersApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        AuthenticatedUser anotherUser = loginAsNewUser();
        String originalName = "original-" + UUID.randomUUID();
        long applicationId = createApplication(owner, originalName);

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer " + anotherUser.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(applicationId, "stolen-name", 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(owner.userId(), persisted.getOwnerUserId());
        assertEquals(originalName, persisted.getName());
        assertEquals(0, persisted.getStatus());
    }

    @Test
    void deletedApplicationShouldNotBeUpdated() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String originalName = "deleted-" + UUID.randomUUID();
        long applicationId = createApplication(owner, originalName);
        Application application = applicationMapper.selectById(applicationId);
        application.setIsDeleted(1);
        application.setDeletedAt(LocalDateTime.now());
        assertEquals(1, applicationMapper.updateById(application));

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(applicationId, "new-name", 1))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(originalName, persisted.getName());
        assertEquals(0, persisted.getStatus());
        assertEquals(1, persisted.getIsDeleted());
    }

    @Test
    void duplicateActiveNameShouldReturnConflictWithoutChangingApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String existingName = "existing-" + UUID.randomUUID();
        String originalName = "original-" + UUID.randomUUID();
        createApplication(owner, existingName);
        long applicationId = createApplication(owner, originalName);

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(applicationId, existingName, 1))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NAME_ALREADY_EXISTS"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(originalName, persisted.getName());
        assertEquals(0, persisted.getStatus());
    }

    @Test
    void tamperedTokenShouldNotUpdateApplication() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String originalName = "original-" + UUID.randomUUID();
        long applicationId = createApplication(owner, originalName);

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer " + tamperSignature(owner.accessToken()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(applicationId, "new-name", 1))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        Application persisted = applicationMapper.selectById(applicationId);
        assertEquals(originalName, persisted.getName());
        assertEquals(0, persisted.getStatus());
    }

    private AuthenticatedUser loginAsNewUser() throws Exception {
        String email = "application-update-" + UUID.randomUUID() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Application Update Test User");
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
