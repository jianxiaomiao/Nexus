package com.nexus.auth.controller;

import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.RegisterResponse;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.auth.service.RegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RegistrationController.class)
class RegistrationControllerTests {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private RegistrationService registrationService;

    @Test
    void normalRegisterTest() throws Exception {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,"test-user-name","test-user-password"
        );
        RegisterResponse mockResp = new RegisterResponse(email,"test-user-name");

        when(registrationService.register(any(RegisterRequest.class))).thenReturn(mockResp);

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerRequest))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("注册成功"))
                .andExpect(jsonPath("$.data.email").value(mockResp.email()))
                .andExpect(jsonPath("$.data.displayName").value(mockResp.displayName()));
    }

    @Test
    void sameEmailRegisterTest() throws Exception {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,"test-user-name","test-user-password"
        );
        EmailAlreadyRegisteredException duplicateException = new EmailAlreadyRegisteredException(
                        new RuntimeException("模拟重复邮箱")
        );

        when(registrationService.register(any(RegisterRequest.class)))
                .thenThrow(duplicateException);

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerRequest))
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code")
                        .value("AUTH_EMAIL_ALREADY_REGISTERED"))
                .andExpect(jsonPath("$.message")
                        .value("该邮箱已被注册"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void notValidRegisterTest() throws Exception {
        RegisterRequest registerRequest = new RegisterRequest(
                "",
                "test-user-name",
                "test-user-password"
        );

        mockMvc.perform(
                        post("/api/auth/register")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(registerRequest))
                )
                //.andDo(print())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.email").value("邮箱不能为空"));

        verifyNoInteractions(registrationService);
    }
}