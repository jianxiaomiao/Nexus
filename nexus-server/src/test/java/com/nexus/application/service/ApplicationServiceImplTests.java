package com.nexus.application.service;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.entity.Application;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.mapper.ApplicationMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
}
