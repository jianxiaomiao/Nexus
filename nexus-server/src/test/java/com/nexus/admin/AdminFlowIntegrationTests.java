package com.nexus.admin;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.admin.dto.UpdateStatusRequest;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.entity.AuthSession;
import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.testing.TestSessionTokens;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AdminFlowIntegrationTests {

    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private UserMapper userMapper;
    @Autowired private ApplicationMapper applicationMapper;
    @Autowired private AuthSessionMapper authSessionMapper;
    @Autowired private JwtTokenService jwtTokenService;
    @Autowired private PasswordEncoder passwordEncoder;

    @Test
    void regularUserShouldBeRejectedFromAdminEndpointsWithForbidden() throws Exception {
        User regular = createUser("USER");
        String token = TestSessionTokens.issue(jwtTokenService, authSessionMapper, regular.getId()).value();

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("USER"));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ADMIN_REQUIRED"));
    }

    @Test
    void adminCanManageUsersAndApplicationsAndBanningRevokesSessions() throws Exception {
        User admin = createUser("ADMIN");
        User target = createUser("USER");
        String adminToken = TestSessionTokens.issue(jwtTokenService, authSessionMapper, admin.getId()).value();
        String targetToken = TestSessionTokens.issue(jwtTokenService, authSessionMapper, target.getId()).value();

        Application app = new Application();
        app.setOwnerUserId(target.getId());
        app.setName("target-app-" + UUID.randomUUID());
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        app.setCreatedAt(now);
        app.setUpdatedAt(now);
        assertEquals(1, applicationMapper.insert(app));

        mockMvc.perform(get("/api/admin/users")
                        .header("Authorization", "Bearer " + adminToken)
                        .param("email", target.getEmail()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.records[0].email").value(target.getEmail()))
                .andExpect(jsonPath("$.data.records[0].applicationCount").value(1));

        // 管理员不能封禁自己
        mockMvc.perform(patch("/api/admin/users/{id}/status", admin.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateStatusRequest(1))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ADMIN_OPERATION"));

        // 封禁普通用户并吊销其会话
        mockMvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateStatusRequest(1))))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + targetToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_FORBIDDEN"));

        AuthSession revoked = authSessionMapper.selectOne(Wrappers.<AuthSession>lambdaQuery()
                .eq(AuthSession::getUserId, target.getId()));
        assertNotNull(revoked.getRevokedAt());

        // 管理员禁用全局应用
        mockMvc.perform(patch("/api/admin/applications/{id}/status", app.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateStatusRequest(1))))
                .andExpect(status().isOk());

        Application updatedApp = applicationMapper.selectById(app.getId());
        assertEquals(1, updatedApp.getStatus());
    }

    private User createUser(String role) {
        User user = new User();
        user.setEmail("admin-test-" + UUID.randomUUID() + "@example.com");
        user.setDisplayName("Test User");
        user.setPasswordHash(passwordEncoder.encode("secret-password"));
        user.setRole(role);
        user.setStatus(0);
        user.setIsDeleted(0);
        assertEquals(1, userMapper.insert(user));
        return user;
    }
}
