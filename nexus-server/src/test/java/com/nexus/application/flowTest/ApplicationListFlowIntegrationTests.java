package com.nexus.application.flowTest;

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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApplicationListFlowIntegrationTests {

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
    void eachLoginTokenListsOnlyItsOwnersNonDeletedApplications() throws Exception {
        AuthenticatedUser firstOwner = loginAsNewUser();
        AuthenticatedUser secondOwner = loginAsNewUser();
        String sharedName = "shared-" + UUID.randomUUID();
        long firstActiveId = createApplication(firstOwner, sharedName);
        long firstDeletedId = createApplication(firstOwner, "deleted-" + UUID.randomUUID());
        long secondActiveId = createApplication(secondOwner, sharedName);

        mockMvc.perform(delete("/api/application/{id}", firstDeletedId)
                        .header("Authorization", "Bearer " + firstOwner.accessToken()))
                .andExpect(status().isOk());

        Application firstActive = applicationMapper.selectById(firstActiveId);
        Application firstDeleted = applicationMapper.selectById(firstDeletedId);
        Application secondActive = applicationMapper.selectById(secondActiveId);
        assertEquals(firstOwner.userId(), firstActive.getOwnerUserId());
        assertEquals(firstOwner.userId(), firstDeleted.getOwnerUserId());
        assertEquals(secondOwner.userId(), secondActive.getOwnerUserId());
        assertEquals(1, firstDeleted.getIsDeleted());

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer " + firstOwner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(firstActiveId))
                .andExpect(jsonPath("$.data.records[0].name").value(sharedName));

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer " + secondOwner.accessToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.records.length()").value(1))
                .andExpect(jsonPath("$.data.records[0].id").value(secondActiveId))
                .andExpect(jsonPath("$.data.records[0].name").value(sharedName));
    }

    private AuthenticatedUser loginAsNewUser() throws Exception {
        String email = "application-list-" + UUID.randomUUID() + "@example.com";
        User user = new User();
        user.setEmail(email);
        user.setDisplayName("Application List Test User");
        user.setPasswordHash(passwordEncoder.encode(TEST_PASSWORD));
        assertEquals(1, userMapper.insert(user));

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new LoginRequest(email, TEST_PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        String token = objectMapper.readTree(result.getResponse().getContentAsString())
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

    private record AuthenticatedUser(long userId, String accessToken) {}
}
