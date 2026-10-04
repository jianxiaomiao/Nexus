package com.nexus.auth.ApiKeyAuthenticator;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
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
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthenticatorTests {
    private final ApiKeyCredentialGenerator generator = new ApiKeyCredentialGenerator();

    @Mock private ApiKeyMapper apiKeyMapper;
    @Mock private ApplicationMapper applicationMapper;
    @Mock private UserMapper userMapper;

    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ApiKeyMapper.class.getName());
        assistant.setCurrentNamespace(ApiKeyMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, ApiKey.class);
    }

    private ApiKeyAuthenticator authenticator() {
        return new ApiKeyAuthenticator(apiKeyMapper, applicationMapper, userMapper, generator);
    }

    @Test
    void missingOrMalformedCredentialIsUnauthorizedWithoutDatabaseAccess() {
        assertThrows(InvalidApiKeyCredentialException.class, () -> authenticator().authenticate(null));
        assertThrows(InvalidApiKeyCredentialException.class, () -> authenticator().authenticate(" "));
        assertThrows(InvalidApiKeyCredentialException.class, () -> authenticator().authenticate("nxk_v1_invalid"));
        verifyNoInteractions(apiKeyMapper, applicationMapper, userMapper);
    }

    @Test
    void unknownPublicIdIsUnauthorized() {
        GeneratedApiKey generated = generator.generate();
        when(apiKeyMapper.selectOne(any())).thenReturn(null);

        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
        verifyNoInteractions(applicationMapper, userMapper);
    }

    @Test
    void wrongSecretOnDisabledKeyStillReturnsUnauthorized() {
        GeneratedApiKey generated = generator.generate();
        ApiKey key = key(generated);
        key.setStatus(1);
        when(apiKeyMapper.selectOne(any())).thenReturn(key);
        String wrongSecret = "A".repeat(43);
        String fullKey = "nxk_v1_" + generated.publicId() + "_" + wrongSecret;

        assertThrows(InvalidApiKeyCredentialException.class, () -> authenticator().authenticate(fullKey));
        verifyNoInteractions(applicationMapper, userMapper);
    }

    @Test
    void deletedKeyIsUnauthorized() {
        GeneratedApiKey generated = generator.generate();
        ApiKey key = key(generated);
        key.setIsDeleted(1);
        when(apiKeyMapper.selectOne(any())).thenReturn(key);

        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
        verifyNoInteractions(applicationMapper, userMapper);
    }

    @Test
    void disabledKeyIsForbiddenAfterSecretVerification() {
        GeneratedApiKey generated = generator.generate();
        ApiKey key = key(generated);
        key.setStatus(1);
        when(apiKeyMapper.selectOne(any())).thenReturn(key);
        when(applicationMapper.selectById(7L)).thenReturn(application());
        when(userMapper.selectById(9L)).thenReturn(owner());

        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
    }

    @Test
    void missingOrDeletedParentIsUnauthorized() {
        GeneratedApiKey generated = generator.generate();
        when(apiKeyMapper.selectOne(any())).thenReturn(key(generated));
        when(applicationMapper.selectById(7L)).thenReturn(null);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));

        Application deleted = application();
        deleted.setIsDeleted(1);
        when(applicationMapper.selectById(7L)).thenReturn(deleted);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
        verifyNoInteractions(userMapper);
    }

    @Test
    void disabledParentIsForbiddenAndReenabledParentRestoresIdentity() {
        GeneratedApiKey generated = generator.generate();
        when(apiKeyMapper.selectOne(any())).thenReturn(key(generated));
        Application application = application();
        application.setStatus(1);
        when(applicationMapper.selectById(7L)).thenReturn(application);
        when(userMapper.selectById(9L)).thenReturn(owner());

        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
        application.setStatus(0);
        assertEquals(new ApiKeyIdentity(5L, 7L), authenticator().authenticate(generated.plaintextKey()));
    }

    @Test
    void missingOrDeletedOwnerIsUnauthorized() {
        GeneratedApiKey generated = generator.generate();
        when(apiKeyMapper.selectOne(any())).thenReturn(key(generated));
        when(applicationMapper.selectById(7L)).thenReturn(application());
        when(userMapper.selectById(9L)).thenReturn(null);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));

        User deleted = owner();
        deleted.setIsDeleted(1);
        when(userMapper.selectById(9L)).thenReturn(deleted);
        assertThrows(InvalidApiKeyCredentialException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
    }

    @Test
    void bannedOwnerIsForbiddenAndUnbanningRestoresTheSameKey() {
        GeneratedApiKey generated = generator.generate();
        when(apiKeyMapper.selectOne(any())).thenReturn(key(generated));
        when(applicationMapper.selectById(7L)).thenReturn(application());
        User owner = owner();
        owner.setStatus(1);
        when(userMapper.selectById(9L)).thenReturn(owner);

        assertThrows(ApiKeyForbiddenException.class,
                () -> authenticator().authenticate(generated.plaintextKey()));
        owner.setStatus(0);
        assertEquals(new ApiKeyIdentity(5L, 7L), authenticator().authenticate(generated.plaintextKey()));
    }

    private ApiKey key(GeneratedApiKey generated) {
        ApiKey key = new ApiKey();
        key.setId(5L);
        key.setApplicationId(7L);
        key.setPublicId(generated.publicId());
        key.setSecretHash(generated.secretHash());
        key.setStatus(0);
        key.setIsDeleted(0);
        return key;
    }

    private Application application() {
        Application application = new Application();
        application.setId(7L);
        application.setOwnerUserId(9L);
        application.setStatus(0);
        application.setIsDeleted(0);
        return application;
    }

    private User owner() {
        User owner = new User();
        owner.setId(9L);
        owner.setStatus(0);
        owner.setIsDeleted(0);
        return owner;
    }
}
