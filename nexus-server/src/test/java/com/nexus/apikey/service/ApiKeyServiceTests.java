package com.nexus.apikey.service;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.nexus.apikey.credential.ApiKeyCredentialGenerator;
import com.nexus.apikey.credential.GeneratedApiKey;
import com.nexus.apikey.dto.ApiKeyResponse;
import com.nexus.apikey.dto.CreateApiKeyRequest;
import com.nexus.apikey.dto.CreateApiKeyResponse;
import com.nexus.apikey.dto.DeleteApiKeyRequest;
import com.nexus.apikey.dto.UpdateApiKeyRequest;
import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.exception.ApiKeyNameAlreadyExistsException;
import com.nexus.apikey.exception.ApiKeyNotFoundException;
import com.nexus.apikey.exception.InvalidApiKeyDeleteException;
import com.nexus.apikey.exception.InvalidApiKeyUpdateException;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationDisabledException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTests {
    @Mock
    private ApiKeyMapper apiKeyMapper;

    @Mock
    private ApplicationMapper applicationMapper;

    @Mock
    private ShortLinkMapper shortLinkMapper;

    @Mock
    private ApiKeyCredentialGenerator credentialGenerator;

    @InjectMocks
    private ApiKeyService service;

    @BeforeAll
    static void initializeTableInfo() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ApplicationMapper.class.getName());
        assistant.setCurrentNamespace(ApplicationMapper.class.getName());
        TableInfoHelper.initTableInfo(assistant, Application.class);

        MapperBuilderAssistant keyAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ApiKeyMapper.class.getName());
        keyAssistant.setCurrentNamespace(ApiKeyMapper.class.getName());
        TableInfoHelper.initTableInfo(keyAssistant, ApiKey.class);

        MapperBuilderAssistant linkAssistant = new MapperBuilderAssistant(
                new MybatisConfiguration(), ShortLinkMapper.class.getName());
        linkAssistant.setCurrentNamespace(ShortLinkMapper.class.getName());
        TableInfoHelper.initTableInfo(linkAssistant, ShortLink.class);
    }

    @Test
    void missingOrUnownedApplicationCannotCreateKey() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertThrows(ApplicationNotFoundException.class,
                () -> service.createMyApiKey(42L, new CreateApiKeyRequest("key", 7L)));

        verifyNoInteractions(credentialGenerator, apiKeyMapper);
    }

    @Test
    void disabledApplicationCannotCreateKey() {
        Application application = new Application();
        application.setStatus(1);
        when(applicationMapper.selectOne(any())).thenReturn(application);

        assertThrows(ApplicationDisabledException.class,
                () -> service.createMyApiKey(42L, new CreateApiKeyRequest("key", 7L)));

        verifyNoInteractions(credentialGenerator, apiKeyMapper);
    }

    @Test
    void successfulCreationReturnsPlaintextOnceWithoutExposingHash() {
        Application application = new Application();
        application.setStatus(0);
        when(applicationMapper.selectOne(any())).thenReturn(application);
        when(credentialGenerator.generate()).thenReturn(
                new GeneratedApiKey("public-id", "stored-hash", "masked-preview", "complete-key"));
        when(apiKeyMapper.insert(any(ApiKey.class))).thenAnswer(invocation -> {
            ApiKey key = invocation.getArgument(0);
            assertEquals(7L, key.getApplicationId());
            assertEquals("named key", key.getName());
            assertEquals("public-id", key.getPublicId());
            assertEquals("stored-hash", key.getSecretHash());
            assertEquals("masked-preview", key.getKeyPreview());
            assertEquals(0, key.getStatus());
            assertEquals(0, key.getIsDeleted());
            assertNotNull(key.getCreatedAt());
            key.setId(99L);
            return 1;
        });

        CreateApiKeyResponse response = service.createMyApiKey(
                42L, new CreateApiKeyRequest("  named key  ", 7L));

        assertEquals(99L, response.id());
        assertEquals("complete-key", response.apiKey());
        assertEquals("public-id", response.publicId());
        assertEquals("masked-preview", response.keyPreview());
        assertFalse(response.toString().contains(response.apiKey()));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Application>> queryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectOne(queryCaptor.capture());
        LambdaQueryWrapper<Application> query = queryCaptor.getValue();
        String where = query.getSqlSegment();
        assertTrue(where.contains("owner_user_id"));
        assertTrue(where.contains("is_deleted"));
        assertTrue(where.endsWith("FOR UPDATE"));
        assertTrue(query.getParamNameValuePairs().containsValue(42L));
        assertTrue(query.getParamNameValuePairs().containsValue(7L));
        assertTrue(query.getParamNameValuePairs().containsValue(0));
    }

    @Test
    void duplicateKeyNameIsReportedAsConflict() {
        Application application = new Application();
        application.setStatus(0);
        when(applicationMapper.selectOne(any())).thenReturn(application);
        when(credentialGenerator.generate()).thenReturn(
                new GeneratedApiKey("public-id", "stored-hash", "masked-preview", "complete-key"));
        when(apiKeyMapper.insert(any(ApiKey.class)))
                .thenThrow(new DuplicateKeyException("duplicate key"));

        assertThrows(ApiKeyNameAlreadyExistsException.class,
                () -> service.createMyApiKey(42L, new CreateApiKeyRequest("key", 7L)));
    }

    @Test
    void invalidUpdateDoesNotTouchDatabase() {
        assertThrows(InvalidApiKeyUpdateException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest(null, 7L, 9L, null)));
        assertThrows(InvalidApiKeyUpdateException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest("  ", 7L, 9L, null)));
        assertThrows(InvalidApiKeyUpdateException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest(null, 7L, 9L, 2)));
        verifyNoInteractions(applicationMapper, apiKeyMapper);
    }

    @Test
    void missingOrUnownedApplicationCannotUpdateKey() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertThrows(ApplicationNotFoundException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest("new name", 7L, 9L, null)));
        verifyNoInteractions(apiKeyMapper);
    }

    @Test
    void disabledApplicationStillAllowsOwnerToListKeys() {
        Application application = new Application();
        application.setStatus(1);
        when(applicationMapper.selectOne(any())).thenReturn(application);
        when(apiKeyMapper.selectPage(any(), any())).thenReturn(new com.baomidou.mybatisplus.extension.plugins.pagination.Page<ApiKey>(1, 10));

        var response = service.listMyApiKeys(42L, 7L, 1, 10, null);

        assertTrue(response.records().isEmpty());
        verify(apiKeyMapper).selectPage(any(), any());
    }

    @Test
    void disabledApplicationStillAllowsOwnerToDisableItsKey() {
        Application application = new Application();
        application.setStatus(1);
        when(applicationMapper.selectOne(any())).thenReturn(application);
        when(apiKeyMapper.update(any(), any())).thenReturn(1);
        ApiKey stored = new ApiKey();
        stored.setId(9L);
        stored.setApplicationId(7L);
        stored.setName("key");
        stored.setPublicId("public-id");
        stored.setKeyPreview("masked-preview");
        stored.setStatus(1);
        when(apiKeyMapper.selectById(9L)).thenReturn(stored);

        ApiKeyResponse response = service.updateMyApiKey(
                42L, new UpdateApiKeyRequest(null, 7L, 9L, 1));

        assertEquals(1, response.status());
        assertEquals("masked-preview", response.keyPreview());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Application>> appQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectOne(appQueryCaptor.capture());
        assertTrue(appQueryCaptor.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertTrue(appQueryCaptor.getValue().getParamNameValuePairs().containsValue(42L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ApiKey>> keyUpdateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(apiKeyMapper).update(any(), keyUpdateCaptor.capture());
        LambdaUpdateWrapper<ApiKey> update = keyUpdateCaptor.getValue();
        assertTrue(update.getSqlSegment().contains("application_id"));
        assertTrue(update.getSqlSegment().contains("is_deleted"));
        assertTrue(update.getParamNameValuePairs().containsValue(7L));
        assertTrue(update.getParamNameValuePairs().containsValue(9L));
    }

    @Test
    void missingOrDeletedKeyCannotBeUpdated() {
        when(applicationMapper.selectOne(any())).thenReturn(new Application());
        when(apiKeyMapper.update(any(), any())).thenReturn(0);

        assertThrows(ApiKeyNotFoundException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest("new name", 7L, 9L, null)));
    }

    @Test
    void duplicateNameOnUpdateIsReportedAsConflict() {
        when(applicationMapper.selectOne(any())).thenReturn(new Application());
        when(apiKeyMapper.update(any(), any()))
                .thenThrow(new DuplicateKeyException("duplicate key"));

        assertThrows(ApiKeyNameAlreadyExistsException.class,
                () -> service.updateMyApiKey(42L, new UpdateApiKeyRequest("used name", 7L, 9L, null)));
    }

    @Test
    void invalidDeleteIdsDoNotTouchDatabase() {
        assertThrows(InvalidApiKeyDeleteException.class,
                () -> service.deleteMyApiKey(42L, new DeleteApiKeyRequest(0L, 9L)));
        assertThrows(InvalidApiKeyDeleteException.class,
                () -> service.deleteMyApiKey(42L, new DeleteApiKeyRequest(7L, null)));
        verifyNoInteractions(applicationMapper, apiKeyMapper);
    }

    @Test
    void missingOrUnownedApplicationCannotDeleteKey() {
        when(applicationMapper.selectOne(any())).thenReturn(null);

        assertThrows(ApplicationNotFoundException.class,
                () -> service.deleteMyApiKey(42L, new DeleteApiKeyRequest(7L, 9L)));
        verifyNoInteractions(apiKeyMapper);
    }

    @Test
    void disabledApplicationStillAllowsOwnerToSoftDeleteKey() {
        Application application = new Application();
        application.setStatus(1);
        when(applicationMapper.selectOne(any())).thenReturn(application);
        when(apiKeyMapper.update(any(), any())).thenReturn(1);

        service.deleteMyApiKey(42L, new DeleteApiKeyRequest(7L, 9L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<Application>> appQueryCaptor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(applicationMapper).selectOne(appQueryCaptor.capture());
        assertTrue(appQueryCaptor.getValue().getSqlSegment().endsWith("FOR UPDATE"));
        assertTrue(appQueryCaptor.getValue().getParamNameValuePairs().containsValue(42L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ApiKey>> keyUpdateCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(apiKeyMapper).update(any(), keyUpdateCaptor.capture());
        LambdaUpdateWrapper<ApiKey> update = keyUpdateCaptor.getValue();
        assertTrue(update.getSqlSegment().contains("application_id"));
        assertTrue(update.getSqlSegment().contains("is_deleted"));
        assertTrue(update.getParamNameValuePairs().containsValue(7L));
        assertTrue(update.getParamNameValuePairs().containsValue(9L));
        assertTrue(update.getSqlSet().contains("deleted_at"));
        assertTrue(update.getParamNameValuePairs().containsValue(1));
        verify(shortLinkMapper).update(any(), any(LambdaUpdateWrapper.class));
    }

    @Test
    void missingOrAlreadyDeletedKeyCannotBeDeletedAgain() {
        when(applicationMapper.selectOne(any())).thenReturn(new Application());
        when(apiKeyMapper.update(any(), any())).thenReturn(0);

        assertThrows(ApiKeyNotFoundException.class,
                () -> service.deleteMyApiKey(42L, new DeleteApiKeyRequest(7L, 9L)));
    }
}
