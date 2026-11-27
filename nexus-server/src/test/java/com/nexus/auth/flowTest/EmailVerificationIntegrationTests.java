package com.nexus.auth.flowTest;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.SendRegisterEmailCodeRequest;
import com.nexus.auth.entity.EmailVerificationCode;
import com.nexus.auth.mapper.EmailVerificationCodeMapper;
import com.nexus.auth.service.EmailSender;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class EmailVerificationIntegrationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EmailVerificationCodeMapper codeMapper;
    @Autowired private UserMapper userMapper;

    @MockitoBean private EmailSender emailSender;

    private final List<String> createdEmails = new ArrayList<>();

    @AfterEach
    void cleanup() {
        for (String email : createdEmails) {
            userMapper.delete(Wrappers.<User>lambdaQuery().eq(User::getEmail, email));
            codeMapper.delete(Wrappers.<EmailVerificationCode>lambdaQuery().eq(EmailVerificationCode::getEmail, email));
        }
        createdEmails.clear();
    }

    @Test
    void sendCodeShouldEnforceCooldownAndRegisterShouldVerifyAndConsumeCode() throws Exception {
        String email = "verify-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(email);
        AtomicReference<String> capturedCode = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedCode.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendVerificationCode(eq(email), anyString(), anyInt());

        mockMvc.perform(post("/api/auth/register/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendRegisterEmailCodeRequest(email))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        assertNotNull(capturedCode.get());
        assertEquals(6, capturedCode.get().length());

        // 60 秒内重复发送应被限流
        mockMvc.perform(post("/api/auth/register/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendRegisterEmailCodeRequest(email))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_CODE_RATE_LIMITED"));

        // 错误验证码应被拒绝，且失败次数持久化
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "Verify User", "password123", "000000"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_EMAIL_CODE"));

        EmailVerificationCode afterOneFail = codeMapper.selectOne(Wrappers.<EmailVerificationCode>lambdaQuery()
                .eq(EmailVerificationCode::getEmail, email)
                .orderByDesc(EmailVerificationCode::getId)
                .last("LIMIT 1"));
        assertEquals(1, afterOneFail.getAttemptCount());

        // 正确验证码注册成功
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "Verify User", "password123", capturedCode.get()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        User created = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getEmail, email));
        assertNotNull(created);
        assertNotNull(created.getEmailVerifiedAt());
        assertEquals("USER", created.getRole());

        EmailVerificationCode consumed = codeMapper.selectOne(Wrappers.<EmailVerificationCode>lambdaQuery()
                .eq(EmailVerificationCode::getEmail, email)
                .orderByDesc(EmailVerificationCode::getId)
                .last("LIMIT 1"));
        assertNotNull(consumed.getConsumedAt());
    }

    @Test
    void expiredOrMaxAttemptedCodeShouldBeRejected() throws Exception {
        String email = "expired-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(email);
        AtomicReference<String> capturedCode = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedCode.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendVerificationCode(eq(email), anyString(), anyInt());

        mockMvc.perform(post("/api/auth/register/email-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new SendRegisterEmailCodeRequest(email))))
                .andExpect(status().isOk());

        // 连续输错 5 次后，即使再用正确验证码也必须被拒绝
        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/auth/register")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new RegisterRequest(email, "Retry User", "password123", "999999"))))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("AUTH_INVALID_EMAIL_CODE"));
        }

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "Retry User", "password123", capturedCode.get()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_EMAIL_CODE"));

        // 过期时间校验
        codeMapper.update(null, Wrappers.<EmailVerificationCode>lambdaUpdate()
                .eq(EmailVerificationCode::getEmail, email)
                .set(EmailVerificationCode::getAttemptCount, 0)
                .set(EmailVerificationCode::getExpiresAt, LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new RegisterRequest(email, "Expired User", "password123", capturedCode.get()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_EMAIL_CODE"));
    }
}
