package com.nexus.application.controller;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.dto.ApplicationResponse;
import com.nexus.application.dto.UpdateApplicationRequest;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.exception.ApplicationNotFoundException;
import com.nexus.application.exception.InvalidApplicationIdException;
import com.nexus.application.exception.InvalidApplicationUpdateException;
import com.nexus.application.service.ApplicationService;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.web.BearerUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationController.class)
class ApplicationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationService applicationService;

    @MockitoBean
    private BearerUserIdResolver bearerUserIdResolver;

    @Test
    void validTokenAndNameShouldCreateApplicationForAuthenticatedOwner() throws Exception {
        CreateApplicationRequest request = new CreateApplicationRequest("appA");
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.createApplication(eq(42L), any(CreateApplicationRequest.class)))
                .thenReturn(new CreateApplicationResponse(100L, "appA"));

        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("应用创建成功"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("appA"));

        verify(bearerUserIdResolver).resolve("Bearer valid-token");
        verify(applicationService).createApplication(42L, request);
    }

    @Test
    void blankNameShouldReturnValidationErrorWithoutCallingDependencies() throws Exception {
        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApplicationRequest(" "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.name").value("应用名称不能为空"));

        verifyNoInteractions(bearerUserIdResolver, applicationService);
    }

    @Test
    void missingTokenShouldReturnUnauthorizedWithoutCreatingApplication() throws Exception {
        when(bearerUserIdResolver.resolve(null)).thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(post("/api/application/create")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new CreateApplicationRequest("appA"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verifyNoInteractions(applicationService);
    }

    @Test
    void duplicateNameShouldReturnConflict() throws Exception {
        CreateApplicationRequest request = new CreateApplicationRequest("appA");
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.createApplication(42L, request))
                .thenThrow(new ApplicationNameAlreadyExistsException(
                        new IllegalStateException("duplicate name")));

        mockMvc.perform(post("/api/application/create")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NAME_ALREADY_EXISTS"));
    }

    @Test
    void validTokenShouldListApplicationsForAuthenticatedOwner() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 9, 26, 10, 30);
        LocalDateTime updatedAt = LocalDateTime.of(2026, 9, 26, 11, 0);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.listMyApplications(42L, 1, 10, null)).thenReturn(new com.nexus.common.web.ListPage<>(2, 1, 10, List.of(
                new ApplicationResponse(100L, "appA", 0, createdAt, updatedAt),
                new ApplicationResponse(101L, "appB", 1, createdAt, updatedAt))));

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("查询成功"))
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.records[0].id").value(100))
                .andExpect(jsonPath("$.data.records[0].name").value("appA"))
                .andExpect(jsonPath("$.data.records[0].status").value(0))
                .andExpect(jsonPath("$.data.records[0].createdAt").value("2026-09-26T10:30:00"))
                .andExpect(jsonPath("$.data.records[0].updatedAt").value("2026-09-26T11:00:00"))
                .andExpect(jsonPath("$.data.records[1].id").value(101))
                .andExpect(jsonPath("$.data.records[1].name").value("appB"))
                .andExpect(jsonPath("$.data.records[1].status").value(1));

        verify(bearerUserIdResolver).resolve("Bearer valid-token");
        verify(applicationService).listMyApplications(42L, 1, 10, null);
    }

    @Test
    void noApplicationsShouldReturnEmptyList() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.listMyApplications(42L, 1, 10, null)).thenReturn(new com.nexus.common.web.ListPage<>(0, 1, 10, List.of()));

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.records.length()").value(0));

        verify(applicationService).listMyApplications(42L, 1, 10, null);
    }

    @Test
    void missingTokenShouldReturnUnauthorizedWithoutListingApplications() throws Exception {
        when(bearerUserIdResolver.resolve(null)).thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(get("/api/application"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve(null);
        verifyNoInteractions(applicationService);
    }

    @Test
    void invalidTokenShouldReturnUnauthorizedWithoutListingApplications() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer invalid-token"))
                .thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(get("/api/application")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve("Bearer invalid-token");
        verifyNoInteractions(applicationService);
    }

    @Test
    void validTokenShouldUpdateApplicationForAuthenticatedOwner() throws Exception {
        UpdateApplicationRequest request = new UpdateApplicationRequest(100L, "renamed-app", 1);
        ApplicationResponse response = new ApplicationResponse(
                100L, "renamed-app", 1,
                LocalDateTime.of(2026, 9, 26, 10, 0),
                LocalDateTime.of(2026, 9, 26, 11, 0));
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.updateMyApplication(42L, request)).thenReturn(response);

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("更新成功"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.name").value("renamed-app"))
                .andExpect(jsonPath("$.data.status").value(1))
                .andExpect(jsonPath("$.data.createdAt").value("2026-09-26T10:00:00"))
                .andExpect(jsonPath("$.data.updatedAt").value("2026-09-26T11:00:00"));

        verify(bearerUserIdResolver).resolve("Bearer valid-token");
        verify(applicationService).updateMyApplication(42L, request);
    }

    @Test
    void invalidUpdateDtoShouldReturnBadRequestBeforeResolvingToken() throws Exception {
        List<UpdateApplicationRequest> invalidRequests = List.of(
                new UpdateApplicationRequest(null, "appA", null),
                new UpdateApplicationRequest(0L, "appA", null),
                new UpdateApplicationRequest(100L, "a".repeat(65), null),
                new UpdateApplicationRequest(100L, null, 2));

        for (UpdateApplicationRequest request : invalidRequests) {
            mockMvc.perform(put("/api/application")
                            .header("Authorization", "Bearer valid-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(request)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        verifyNoInteractions(bearerUserIdResolver, applicationService);
    }

    @Test
    void missingTokenShouldReturnUnauthorizedWithoutUpdating() throws Exception {
        when(bearerUserIdResolver.resolve(null)).thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(put("/api/application")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(100L, "appA", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve(null);
        verifyNoInteractions(applicationService);
    }

    @Test
    void invalidTokenShouldReturnUnauthorizedWithoutUpdating() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer invalid-token"))
                .thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer invalid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new UpdateApplicationRequest(100L, "appA", null))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve("Bearer invalid-token");
        verifyNoInteractions(applicationService);
    }

    @Test
    void applicationNotOwnedOrAbsentShouldReturnNotFound() throws Exception {
        UpdateApplicationRequest request = new UpdateApplicationRequest(100L, "appA", null);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.updateMyApplication(42L, request))
                .thenThrow(new ApplicationNotFoundException());

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        verify(applicationService).updateMyApplication(42L, request);
    }

    @Test
    void duplicateNameDuringUpdateShouldReturnConflict() throws Exception {
        UpdateApplicationRequest request = new UpdateApplicationRequest(100L, "appA", null);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.updateMyApplication(42L, request))
                .thenThrow(new ApplicationNameAlreadyExistsException(
                        new IllegalStateException("duplicate name")));

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("APPLICATION_NAME_ALREADY_EXISTS"));

        verify(applicationService).updateMyApplication(42L, request);
    }

    @Test
    void emptyUpdateShouldReturnDomainBadRequest() throws Exception {
        UpdateApplicationRequest request = new UpdateApplicationRequest(100L, null, null);
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        when(applicationService.updateMyApplication(42L, request))
                .thenThrow(new InvalidApplicationUpdateException("至少提供一个要更新的字段"));

        mockMvc.perform(put("/api/application")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_APPLICATION_UPDATE"));

        verify(applicationService).updateMyApplication(42L, request);
    }

    @Test
    void validTokenShouldDeleteApplicationUsingPathIdWithoutRequestBody() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);

        mockMvc.perform(delete("/api/application/100")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("删除成功"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(bearerUserIdResolver).resolve("Bearer valid-token");
        verify(applicationService).deleteMyApplication(42L, 100L);
    }

    @Test
    void missingTokenShouldReturnUnauthorizedWithoutDeleting() throws Exception {
        when(bearerUserIdResolver.resolve(null)).thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(delete("/api/application/100"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve(null);
        verifyNoInteractions(applicationService);
    }

    @Test
    void invalidTokenShouldReturnUnauthorizedWithoutDeleting() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer invalid-token"))
                .thenThrow(new InvalidAccessTokenException());

        mockMvc.perform(delete("/api/application/100")
                        .header("Authorization", "Bearer invalid-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_ACCESS_TOKEN"));

        verify(bearerUserIdResolver).resolve("Bearer invalid-token");
        verifyNoInteractions(applicationService);
    }

    @Test
    void absentOrUnownedApplicationShouldReturnNotFoundOnDelete() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        doThrow(new ApplicationNotFoundException())
                .when(applicationService).deleteMyApplication(42L, 100L);

        mockMvc.perform(delete("/api/application/100")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("APPLICATION_NOT_FOUND"));

        verify(applicationService).deleteMyApplication(42L, 100L);
    }

    @Test
    void nonPositivePathIdShouldReturnBadRequest() throws Exception {
        when(bearerUserIdResolver.resolve("Bearer valid-token")).thenReturn(42L);
        doThrow(new InvalidApplicationIdException())
                .when(applicationService).deleteMyApplication(42L, 0L);
        doThrow(new InvalidApplicationIdException())
                .when(applicationService).deleteMyApplication(42L, -1L);

        for (String path : List.of("/api/application/0", "/api/application/-1")) {
            mockMvc.perform(delete(path)
                            .header("Authorization", "Bearer valid-token"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_APPLICATION_ID"));
        }

        verify(applicationService).deleteMyApplication(42L, 0L);
        verify(applicationService).deleteMyApplication(42L, -1L);
    }
}
