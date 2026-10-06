package com.nexus.application.service;

import com.nexus.apikey.entity.ApiKey;
import com.nexus.apikey.mapper.ApiKeyMapper;
import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.dto.ApplicationResponse;
import com.nexus.application.dto.UpdateApplicationRequest;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.shortlink.entity.ShortLink;
import com.nexus.shortlink.mapper.ShortLinkMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTests {

    @Mock
    private ApplicationMapper applicationMapper;

    @Mock
    private ApiKeyMapper apiKeyMapper;

    @Mock
    private ShortLinkMapper shortLinkMapper;

    private ApplicationService applicationService;

    @BeforeAll
    static void initializeApplicationTableInfo() {
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

    @BeforeEach
    void setUp() {
        applicationService = new ApplicationService(applicationMapper, apiKeyMapper, shortLinkMapper);
    }

    @Test
    void createShouldUseOwnerAndReturnGeneratedId() {
        when(applicationMapper.insert(any(Application.class))).thenAnswer(invocation -> {
            Application application = invocation.getArgument(0);
            assertNull(application.getId(), "自增主键应由数据库生成");
            assertEquals(42L, application.getOwnerUserId());
            assertEquals("appA", application.getName());
            application.setId(100L);
            return 1;
        });

        CreateApplicationResponse response = applicationService.createApplication(
                42L, new CreateApplicationRequest("appA"));

        assertEquals(100L, response.id());
        assertEquals("appA", response.name());
        verify(applicationMapper).insert(any(Application.class));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void createShouldStripNameBeforePersisting() {
        when(applicationMapper.insert(any(Application.class))).thenAnswer(invocation -> {
            Application application = invocation.getArgument(0);
            assertEquals("appA", application.getName());
            application.setId(101L);
            return 1;
        });

        CreateApplicationResponse response = applicationService.createApplication(
                42L, new CreateApplicationRequest("  appA  "));

        assertEquals(101L, response.id());
        assertEquals("appA", response.name());
        verify(applicationMapper).insert(any(Application.class));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void createShouldTranslateDuplicateName() {
        DuplicateKeyException databaseException = new DuplicateKeyException("duplicate name");
        when(applicationMapper.insert(any(Application.class))).thenThrow(databaseException);

        ApplicationNameAlreadyExistsException exception = assertThrows(
                ApplicationNameAlreadyExistsException.class,
                () -> applicationService.createApplication(42L, new CreateApplicationRequest("appA")));

        assertInstanceOf(DuplicateKeyException.class, exception.getCause());
        assertEquals("应用名重复", exception.getMessage());
        verify(applicationMapper).insert(any(Application.class));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void listShouldMapApplicationsAndScopeQueryToActiveOwner() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 26, 10, 0);
        LocalDateTime updatedAt = createdAt.plusHours(1);
        Application enabled = new Application();
        enabled.setId(100L);
        enabled.setName("enabled-app");
        enabled.setStatus(0);
        enabled.setCreatedAt(createdAt);
        enabled.setUpdatedAt(updatedAt);
        Application disabled = new Application();
        disabled.setId(101L);
        disabled.setName("disabled-app");
        disabled.setStatus(1);
        disabled.setCreatedAt(createdAt);
        disabled.setUpdatedAt(updatedAt);
        when(applicationMapper.selectList(any())).thenReturn(List.of(enabled, disabled));

        List<ApplicationResponse> responses = applicationService.listMyApplications(42L);

        assertEquals(List.of(
                new ApplicationResponse(100L, "enabled-app", 0, createdAt, updatedAt),
                new ApplicationResponse(101L, "disabled-app", 1, createdAt, updatedAt)
        ), responses);
        verify(applicationMapper).selectList(org.mockito.ArgumentMatchers.argThat(wrapper -> {
            if (!(wrapper instanceof LambdaQueryWrapper<?> query)) {
                return false;
            }
            String sql = query.getSqlSegment();
            return sql.contains("owner_user_id")
                    && sql.contains("is_deleted")
                    && sql.contains("created_at DESC")
                    && sql.contains("id DESC")
                    && query.getParamNameValuePairs().containsValue(42L)
                    && query.getParamNameValuePairs().containsValue(0);
        }));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void listShouldReturnEmptyListWhenOwnerHasNoActiveApplications() {
        when(applicationMapper.selectList(any())).thenReturn(List.of());

        List<ApplicationResponse> responses = applicationService.listMyApplications(42L);

        assertTrue(responses.isEmpty());
        verify(applicationMapper).selectList(any());
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void updateShouldScopeWriteToActiveOwnerAndReturnPersistedResponse() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 26, 10, 0);
        LocalDateTime updatedAt = createdAt.plusHours(1);
        Application persisted = new Application();
        persisted.setId(100L);
        persisted.setName("renamed-app");
        persisted.setStatus(1);
        persisted.setCreatedAt(createdAt);
        persisted.setUpdatedAt(updatedAt);
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        when(applicationMapper.selectById(100L)).thenReturn(persisted);

        ApplicationResponse response = applicationService.updateMyApplication(
                42L, new UpdateApplicationRequest(100L, "  renamed-app  ", 1));

        assertEquals(new ApplicationResponse(100L, "renamed-app", 1, createdAt, updatedAt), response);
        verify(applicationMapper).update(isNull(), argThat(wrapper -> {
            if (!(wrapper instanceof LambdaUpdateWrapper<?> update)) {
                return false;
            }
            String where = update.getExpression().getSqlSegment();
            String set = update.getSqlSet();
            return where.contains("owner_user_id")
                    && where.contains("is_deleted")
                    && where.contains("id")
                    && set.contains("name")
                    && set.contains("status")
                    && set.contains("updated_at")
                    && update.getParamNameValuePairs().containsValue(100L)
                    && update.getParamNameValuePairs().containsValue(42L)
                    && update.getParamNameValuePairs().containsValue("renamed-app")
                    && update.getParamNameValuePairs().containsValue(1);
        }));
        verify(applicationMapper).selectById(100L);
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void statusOnlyUpdateShouldNotWriteName() {
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ApplicationNotFoundException.class,
                () -> applicationService.updateMyApplication(
                        42L, new UpdateApplicationRequest(100L, null, 1)));

        verify(applicationMapper).update(isNull(), argThat(wrapper -> {
            if (!(wrapper instanceof LambdaUpdateWrapper<?> update)) {
                return false;
            }
            return !update.getSqlSet().contains("name")
                    && update.getSqlSet().contains("status");
        }));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void nameOnlyUpdateShouldNotWriteStatus() {
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ApplicationNotFoundException.class,
                () -> applicationService.updateMyApplication(
                        42L, new UpdateApplicationRequest(100L, " appA ", null)));

        verify(applicationMapper).update(isNull(), argThat(wrapper -> {
            if (!(wrapper instanceof LambdaUpdateWrapper<?> update)) {
                return false;
            }
            return update.getSqlSet().contains("name")
                    && !update.getSqlSet().contains("status")
                    && update.getParamNameValuePairs().containsValue("appA");
        }));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void updateShouldReturnNotFoundWithoutReadingWhenNoOwnedActiveRowMatches() {
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ApplicationNotFoundException.class,
                () -> applicationService.updateMyApplication(
                        42L, new UpdateApplicationRequest(100L, "appA", null)));

        verify(applicationMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void updateShouldTranslateDuplicateNameWithoutReading() {
        DuplicateKeyException databaseException = new DuplicateKeyException("duplicate name");
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class)))
                .thenThrow(databaseException);

        ApplicationNameAlreadyExistsException exception = assertThrows(
                ApplicationNameAlreadyExistsException.class,
                () -> applicationService.updateMyApplication(
                        42L, new UpdateApplicationRequest(100L, "appA", null)));

        assertEquals(databaseException, exception.getCause());
        verify(applicationMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(applicationMapper);
    }

    @Test
    void invalidUpdateRequestsShouldBeRejectedBeforePersistence() {
        List<UpdateApplicationRequest> requests = List.of(
                new UpdateApplicationRequest(null, "appA", null),
                new UpdateApplicationRequest(0L, "appA", null),
                new UpdateApplicationRequest(100L, null, null),
                new UpdateApplicationRequest(100L, "   ", null),
                new UpdateApplicationRequest(100L, "a".repeat(65), null),
                new UpdateApplicationRequest(100L, null, 2));

        for (UpdateApplicationRequest request : requests) {
            assertThrows(InvalidApplicationUpdateException.class,
                    () -> applicationService.updateMyApplication(42L, request),
                    () -> "应拒绝非法更新请求: " + request);
        }
        verifyNoInteractions(applicationMapper);
    }

    @Test
    void deleteShouldSoftDeleteApplicationAndItsActiveKeys() {
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(1);
        ApiKey key = new ApiKey();
        key.setId(9L);
        when(apiKeyMapper.selectList(any())).thenReturn(List.of(key));

        assertDoesNotThrow(() -> applicationService.deleteMyApplication(42L, 100L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<Application>> appCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(applicationMapper).update(isNull(), appCaptor.capture());
        LambdaUpdateWrapper<Application> appUpdate = appCaptor.getValue();
        assertTrue(appUpdate.getExpression().getSqlSegment().contains("owner_user_id"));
        assertTrue(appUpdate.getExpression().getSqlSegment().contains("is_deleted"));
        assertTrue(appUpdate.getParamNameValuePairs().containsValue(100L));
        assertTrue(appUpdate.getParamNameValuePairs().containsValue(42L));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaUpdateWrapper<ApiKey>> keyCaptor =
                ArgumentCaptor.forClass(LambdaUpdateWrapper.class);
        verify(apiKeyMapper).update(isNull(), keyCaptor.capture());
        LambdaUpdateWrapper<ApiKey> keyUpdate = keyCaptor.getValue();
        assertTrue(keyUpdate.getExpression().getSqlSegment().contains("application_id"));
        assertTrue(keyUpdate.getExpression().getSqlSegment().contains("is_deleted"));
        assertTrue(keyUpdate.getSqlSet().contains("deleted_at"));
        assertTrue(keyUpdate.getParamNameValuePairs().containsValue(100L));
        assertTrue(keyUpdate.getParamNameValuePairs().containsValue(0));
        assertTrue(keyUpdate.getParamNameValuePairs().containsValue(1));

        LocalDateTime appDeletedAt = appUpdate.getParamNameValuePairs().values().stream()
                .filter(LocalDateTime.class::isInstance)
                .map(LocalDateTime.class::cast)
                .findFirst().orElseThrow();
        assertTrue(keyUpdate.getParamNameValuePairs().containsValue(appDeletedAt));
        verifyNoMoreInteractions(applicationMapper);
        verify(apiKeyMapper).selectList(any());
        verify(shortLinkMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(apiKeyMapper, shortLinkMapper);
    }

    @Test
    void deleteShouldReturnNotFoundWhenNoActiveOwnedApplicationMatches() {
        when(applicationMapper.update(isNull(), any(LambdaUpdateWrapper.class))).thenReturn(0);

        assertThrows(ApplicationNotFoundException.class,
                () -> applicationService.deleteMyApplication(42L, 100L));

        verify(applicationMapper).update(isNull(), any(LambdaUpdateWrapper.class));
        verifyNoMoreInteractions(applicationMapper);
        verifyNoInteractions(apiKeyMapper);
    }

    @Test
    void deleteShouldRejectNonPositiveIdBeforeCallingMapper() {
        for (Long appId : new Long[] {null, 0L, -1L}) {
            assertThrows(InvalidApplicationIdException.class,
                    () -> applicationService.deleteMyApplication(42L, appId));
        }
        verifyNoInteractions(applicationMapper);
        verifyNoInteractions(apiKeyMapper);
    }
}
