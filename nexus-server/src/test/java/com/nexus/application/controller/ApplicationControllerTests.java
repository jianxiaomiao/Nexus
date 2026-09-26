package com.nexus.application.controller;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.exception.ApplicationNameAlreadyExistsException;
import com.nexus.application.service.ApplicationServiceImpl;
import com.nexus.auth.config.JwtConfig;
import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.web.BearerUserIdResolver;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ApplicationController.class)
class ApplicationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ApplicationServiceImpl applicationService;

    @MockitoBean
    private BearerUserIdResolver bearerUserIdResolver;

    // 当前 Controller 仍注入配置类；移除该无用依赖后也应移除这个 Mock。
    @MockitoBean
    private JwtConfig jwtConfig;

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
}
