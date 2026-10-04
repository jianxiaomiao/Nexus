package com.nexus.auth.ApiKeyAuthenticator;

import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.exception.ApiKeyForbiddenException;
import com.nexus.auth.exception.InvalidApiKeyCredentialException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@Transactional
class ApiKeyAuthenticatorIntegrationTests {
    private static final String TEST_JWT_SECRET = Base64.getEncoder().encodeToString(
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    @DynamicPropertySource
    static void jwtProperties(DynamicPropertyRegistry registry) {
        registry.add("nexus.auth.jwt.secret", () -> TEST_JWT_SECRET);
        registry.add("nexus.auth.jwt.issuer", () -> "nexus");
        registry.add("nexus.auth.jwt.access-token-ttl", () -> "30m");
    }

    @Autowired private ApiKeyAuthenticator authenticator;
    @Autowired private ApiKeyCredentialGenerator generator;
    @Autowired private UserMapper userMapper;
    @Autowired private ApplicationMapper applicationMapper;
    @Autowired private ApiKeyMapper apiKeyMapper;

    @Test
    void generatedCredentialAuthenticatesAndLifecycleChangesTakeEffect() {
        User owner = new User();
        owner.setEmail("machine-auth-" + UUID.randomUUID() + "@example.com");
        owner.setDisplayName("Machine auth owner");
        owner.setPasswordHash("test-only-password-hash");
        userMapper.insert(owner);

        Application application = new Application();
        application.setOwnerUserId(owner.getId());
        application.setName("machine-auth-app-" + UUID.randomUUID());
        applicationMapper.insert(application);

        GeneratedApiKey generated = generator.generate();
        ApiKey key = new ApiKey();
        key.setApplicationId(application.getId());
        key.setName("machine-auth-key");
        key.setPublicId(generated.publicId());
        key.setSecretHash(generated.secretHash());
        key.setKeyPreview(generated.keyPreview());
        apiKeyMapper.insert(key);

        assertEquals(new ApiKeyIdentity(key.getId(), application.getId()),
                authenticator.authenticate(generated.plaintextKey()));

        key.setStatus(1);
        apiKeyMapper.updateById(key);
        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator.authenticate(generated.plaintextKey()));
        key.setStatus(0);
        apiKeyMapper.updateById(key);

        application.setStatus(1);
        applicationMapper.updateById(application);
        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator.authenticate(generated.plaintextKey()));
        application.setStatus(0);
        applicationMapper.updateById(application);
        assertEquals(new ApiKeyIdentity(key.getId(), application.getId()),
                authenticator.authenticate(generated.plaintextKey()));

        owner.setStatus(1);
        userMapper.updateById(owner);
        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator.authenticate(generated.plaintextKey()));
        owner.setStatus(0);
        userMapper.updateById(owner);
        assertEquals(new ApiKeyIdentity(key.getId(), application.getId()),
                authenticator.authenticate(generated.plaintextKey()));

        key.setIsDeleted(1);
        key.setDeletedAt(LocalDateTime.now());
        apiKeyMapper.updateById(key);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator.authenticate(generated.plaintextKey()));
    }
}
