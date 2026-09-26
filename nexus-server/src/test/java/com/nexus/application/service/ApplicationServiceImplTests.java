package com.nexus.application.service;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.dto.ApplicationResponse;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.mapper.ApplicationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApplicationServiceImplTests {

    @Mock
    private ApplicationMapper applicationMapper;

    private ApplicationServiceImpl applicationService;

    @BeforeEach
    void setUp() {
        applicationService = new ApplicationServiceImpl(applicationMapper);
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
}
