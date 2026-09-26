package com.nexus.application.flowTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.application.dto.CreateApplicationRequest;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplicationCreationFlowIntegrationTests {

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
    void loginTokenShouldCreateApplicationOwnedByItsUser() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String name = "app-" + UUID.randomUUID();

        MvcResult result = mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateApplicationRequest("  " + name + "  "))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.name").value(name))
                .andExpect(jsonPath("$.data.id").isNumber())
                .andReturn();

        long applicationId = Long.parseLong(objectMapper.readTree(
                result.getResponse().getContentAsString()).at("/data/id").asText());
        Application persisted = applicationMapper.selectById(applicationId);

        assertNotNull(persisted);
        assertEquals(owner.userId(), persisted.getOwnerUserId());
        assertEquals(name, persisted.getName());
    }

    @Test
    void tamperedLoginTokenShouldReturnUnauthorizedWithoutInserting() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String name = "app-" + UUID.randomUUID();
        String tamperedToken = tamperSignature(owner.accessToken());

        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer " + tamperedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new CreateApplicationRequest(name))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        assertNull(applicationMapper.selectOne(Wrappers.<Application>lambdaQuery()
                .eq(Application::getOwnerUserId, owner.userId())
                .eq(Application::getName, name)));
    }

    @Test
    void duplicateNameForSameOwnerShouldReturnConflictWithoutSecondRow() throws Exception {
        AuthenticatedUser owner = loginAsNewUser();
        String name = "app-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new CreateApplicationRequest(name));

        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer " + owner.accessToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NAME_ALREADY_EXISTS"));

        assertEquals(1L, applicationMapper.selectCount(Wrappers.<Application>lambdaQuery()
                .eq(Application::getOwnerUserId, owner.userId())
                .eq(Application::getName, name)));
    }

    @Test
    void sameNameShouldBeAllowedForDifferentAuthenticatedOwners() throws Exception {
        AuthenticatedUser firstOwner = loginAsNewUser();
        AuthenticatedUser secondOwner = loginAsNewUser();
        String name = "shared-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(new CreateApplicationRequest(name));

        for (AuthenticatedUser owner : new AuthenticatedUser[] {firstOwner, secondOwner}) {
            mockMvc.perform(post("/api/application/create")
                            .header("Authorization", "Bearer " + owner.accessToken())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk());

            assertEquals(1L, applicationMapper.selectCount(Wrappers.<Application>lambdaQuery()
                    .eq(Application::getOwnerUserId, owner.userId())
                    .eq(Application::getName, name)));
        }
    }

    private AuthenticatedUser loginAsNewUser() throws Exception {
        String email = "application-flow-" + UUID.randomUUID() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Application Flow Test User");
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

    private String tamperSignature(String token) {
        String[] parts = token.split("\\.", -1);
        char first = parts[2].charAt(0);
        parts[2] = (first == 'A' ? 'B' : 'A') + parts[2].substring(1);
        return String.join(".", parts);
    }

    private record AuthenticatedUser(long userId, String accessToken) {}
}
