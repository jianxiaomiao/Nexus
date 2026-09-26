package com.nexus.auth.controller;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.LoginResponse;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.InvalidCredentialsException;
import com.nexus.auth.service.LoginService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;


@WebMvcTest(LoginController.class)
public class LoginControllerTests {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private LoginService loginService;

    //1.登录成功
    //有效请求，Mock Service 返回 token；断言 200、SUCCESS、登录成功消息以及 accessToken/tokenType/expiresInSeconds。
    @Test
    void normalLoginTest() throws Exception{
        String email = "test-user"+ UUID.randomUUID() + "@example.com";
        String password = "test-user-password";
        LoginRequest loginRequest = new LoginRequest(email,password);
        LoginResponse mockRsp = new LoginResponse(
                "mock-accessToken", "Bearer", 1800L);

        when(loginService.login(any(LoginRequest.class))).thenReturn(mockRsp);

        mockMvc.perform(
                post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest))
        )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.message").value("登录成功"))
                .andExpect(jsonPath("$.data.accessToken").value(mockRsp.accessToken()))
                .andExpect(jsonPath("$.data.tokenType").value(mockRsp.tokenType()))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(mockRsp.expiresInSeconds()));

        verify(loginService).login(loginRequest);

    }
    //2.请求参数校验失败
    //至少验证空邮箱/空密码，再考虑非法邮箱、邮箱超过 254、密码超过 64；断言 400、VALIDATION_ERROR，
    // 并用 verifyNoInteractions(loginService) 确认 Service 没有被调用。
    @Test
    void notValidLoginTest() throws Exception{
        String email = "test-user"+ UUID.randomUUID() + "@example.com";
        String password = "test-user-password";
        LoginRequest nullEmailLoginRequest = new LoginRequest("",password);
        LoginRequest nullPasswordLoginRequest = new LoginRequest(email,"");

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(nullEmailLoginRequest))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.email").value("邮箱不能为空"));

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(nullPasswordLoginRequest))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.password").value("密码不能为空"));

        verifyNoInteractions(loginService);

    }

    @Test
    void invalidEmailShouldReturnBadRequest() throws Exception {
        LoginRequest loginRequest = new LoginRequest(
                "not-an-email",
                "test-user-password"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.email").value("邮箱格式不正确"));

        verifyNoInteractions(loginService);
    }

    @Test
    void emailLongerThan254CharactersShouldReturnBadRequest() throws Exception {
        String overlongEmail = "a".repeat(63)
                + "@"
                + "b".repeat(63)
                + "."
                + "c".repeat(63)
                + "."
                + "d".repeat(63);
        LoginRequest loginRequest = new LoginRequest(
                overlongEmail,
                "test-user-password"
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.email").value("邮箱长度不能超过254个字符"));

        verifyNoInteractions(loginService);
    }

    @Test
    void passwordLongerThan64CharactersShouldReturnBadRequest() throws Exception {
        LoginRequest loginRequest = new LoginRequest(
                "test-user@example.com",
                "p".repeat(65)
        );

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("请求参数校验失败"))
                .andExpect(jsonPath("$.data.password").value("密码长度不能超过64个字符"));

        verifyNoInteractions(loginService);
    }

    //3.凭据无效
    //Mock Service 抛出 InvalidCredentialsException；断言 401、AUTH_INVALID_CREDENTIALS、通用错误消息、data 不存在。
    @Test
    void NoIssuedAccessTokenTest() throws Exception{
        String email = "test-user"+ UUID.randomUUID() + "@example.com";
        String password = "test-user-password";
        LoginRequest loginRequest = new LoginRequest(email,password);

        when(loginService.login(any(LoginRequest.class)))
                .thenThrow(new InvalidCredentialsException());

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CREDENTIALS"))
                .andExpect(jsonPath("$.message").value("邮箱或密码错误"))
                .andExpect(jsonPath("$.data").isEmpty());

    }
    //4.账号被禁止
    //Mock Service 抛出 AccountForbiddenException；断言 403、AUTH_ACCOUNT_FORBIDDEN、封禁消息、data 不存在。
    @Test
    void ForbiddenAccountTest() throws Exception{
        String email = "test-user"+ UUID.randomUUID() + "@example.com";
        String password = "test-user-password";
        LoginRequest loginRequest = new LoginRequest(email,password);

        when(loginService.login(any(LoginRequest.class)))
                .thenThrow(new AccountForbiddenException());

        mockMvc.perform(
                        post("/api/auth/login")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(objectMapper.writeValueAsString(loginRequest))
                )
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AUTH_ACCOUNT_FORBIDDEN"))
                .andExpect(jsonPath("$.message").value("账号已封禁"))
                .andExpect(jsonPath("$.data").isEmpty());

    }
}
