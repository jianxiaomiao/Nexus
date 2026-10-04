package com.nexus.application.service;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ApplicationDeletionRollbackIntegrationTests {
    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
    }

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private ApplicationMapper applicationMapper;

    @Autowired
    private ApplicationService applicationService;

    @MockitoBean
    private ApiKeyMapper apiKeyMapper;

    @Test
    void childUpdateFailureRollsBackParentSoftDeletion() {
        User owner = new User();
        owner.setEmail("rollback-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Rollback Test User");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        Application application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("rollback-" + UUID.randomUUID());
        applicationMapper.insert(application);

        try {
            when(apiKeyMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                    .thenThrow(new IllegalStateException("simulated child update failure"));

            assertThrows(IllegalStateException.class,
                    () -> applicationService.deleteMyApplication(owner.getId(), application.getId()));

            Application persisted = applicationMapper.selectById(application.getId());
            assertEquals(0, persisted.getIsDeleted());
            assertNull(persisted.getDeletedAt());
        } finally {
            applicationMapper.deleteById(application.getId());
            userMapper.deleteById(owner.getId());
        }
    }
}
