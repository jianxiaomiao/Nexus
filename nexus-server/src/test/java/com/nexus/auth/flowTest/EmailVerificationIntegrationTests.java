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
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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

    private record SolvedCaptcha(String id, String answer) {}

    private SolvedCaptcha fetchAndSolveCaptcha() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/register/captcha"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        String id = data.path("captchaId").asText();
        String dataUrl = data.path("imageDataUrl").asText();
        String svg = new String(Base64.getDecoder().decode(
                dataUrl.substring("data:image/svg+xml;base64,".length())), StandardCharsets.UTF_8);
        StringBuilder chars = new StringBuilder();
        Matcher m = Pattern.compile(">([^<]+)</text>").matcher(svg);
        while (m.find()) {
            chars.append(m.group(1));
        }
        Matcher expr = Pattern.compile("^(\\d+)([+\\-×÷])(\\d+)=\\?$").matcher(chars.toString());
        if (!expr.matches()) {
            throw new IllegalStateException("Unexpected captcha SVG expression: " + chars);
        }
        int a = Integer.parseInt(expr.group(1));
        int b = Integer.parseInt(expr.group(3));
        int ans = switch (expr.group(2)) {
            case "+" -> a + b;
            case "-" -> a - b;
            case "×" -> a * b;
            case "÷" -> a / b;
            default -> throw new IllegalStateException("Unexpected operator");
        };
        return new SolvedCaptcha(id, String.valueOf(ans));
    }

    @Test
    void captchaMustPassBeforeSendingEmailAndRegisterConsumesCode() throws Exception {
        String email = "verify-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(email);
        AtomicReference<String> capturedCode = new AtomicReference<>();
        doAnswer(invocation -> {
            capturedCode.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendVerificationCode(eq(email), anyString(), anyInt());

        // 错误图形验证码被拦截且不发邮件，同时立刻消费该 captchaId
        SolvedCaptcha badCaptcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(email, badCaptcha.id(), "99999"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CAPTCHA"));
        verify(emailSender, never()).sendVerificationCode(eq(email), anyString(), anyInt());

        // 同一个 captchaId 即使改填正确答案也已作废
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(email, badCaptcha.id(), badCaptcha.answer()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_INVALID_CAPTCHA"));

        // 获取新图形验证码并正确回答 -> 成功发信
        SolvedCaptcha validCaptcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(email, validCaptcha.id(), validCaptcha.answer()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("SUCCESS"));

        assertNotNull(capturedCode.get());
        assertEquals(6, capturedCode.get().length());

        // 60 秒内重复发送应被限流（即使图形验证码正确）
        SolvedCaptcha cooldownCaptcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(email, cooldownCaptcha.id(), cooldownCaptcha.answer()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_CODE_RATE_LIMITED"));

        // 错误邮箱验证码应被拒绝，且失败次数持久化
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

        SolvedCaptcha captcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(email, captcha.id(), captcha.answer()))))
                .andExpect(status().isOk());

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

    @Test
    void ipWindowLimitAndDailyEmailLimitShouldBlockExcessiveRequests() throws Exception {
        // 同 IP 10 分钟最多 10 次
        String testIp = "203.0.113." + (10 + (int) (Math.random() * 200));
        for (int i = 0; i < 10; i++) {
            String email = "ip-limit-" + i + "-" + UUID.randomUUID() + "@example.com";
            createdEmails.add(email);
            SolvedCaptcha c = fetchAndSolveCaptcha();
            mockMvc.perform(post("/api/auth/register/email-code")
                            .header("X-Forwarded-For", testIp)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(
                                    new SendRegisterEmailCodeRequest(email, c.id(), c.answer()))))
                    .andExpect(status().isOk());
        }
        String eleventhEmail = "ip-limit-11-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(eleventhEmail);
        SolvedCaptcha eleventhCaptcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", testIp)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(eleventhEmail, eleventhCaptcha.id(), eleventhCaptcha.answer()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_CODE_RATE_LIMITED"));

        // 同邮箱 24 小时最多 10 次
        String dailyEmail = "daily-limit-" + UUID.randomUUID() + "@example.com";
        createdEmails.add(dailyEmail);
        LocalDateTime twoMinutesAgo = LocalDateTime.now(ZoneOffset.UTC).minusMinutes(2);
        for (int i = 0; i < 10; i++) {
            EmailVerificationCode row = new EmailVerificationCode();
            row.setEmail(dailyEmail);
            row.setPurpose("REGISTER");
            row.setCodeHash("0".repeat(64));
            row.setExpiresAt(twoMinutesAgo.plusMinutes(5));
            row.setAttemptCount(0);
            row.setCreatedAt(twoMinutesAgo.minusMinutes(i));
            codeMapper.insert(row);
        }
        SolvedCaptcha dailyCaptcha = fetchAndSolveCaptcha();
        mockMvc.perform(post("/api/auth/register/email-code")
                        .header("X-Forwarded-For", "198.51.100.99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new SendRegisterEmailCodeRequest(dailyEmail, dailyCaptcha.id(), dailyCaptcha.answer()))))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("AUTH_EMAIL_CODE_RATE_LIMITED"));
    }
}
