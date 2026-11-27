package com.nexus.auth.controller;

import com.nexus.auth.dto.CaptchaResponse;
import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.RegisterResponse;
import com.nexus.auth.dto.SendRegisterEmailCodeRequest;
import com.nexus.auth.service.ArithmeticCaptchaService;
import com.nexus.auth.service.EmailVerificationService;
import com.nexus.auth.service.RegistrationService;
import com.nexus.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class RegistrationController {
    private final RegistrationService registrationService;
    private final EmailVerificationService emailVerificationService;
    private final ArithmeticCaptchaService captchaService;

    @ResponseStatus(HttpStatus.OK)
    @GetMapping("/register/captcha")
    public ApiResponse<CaptchaResponse> registerCaptcha(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new ApiResponse<>("SUCCESS", "获取成功", captchaService.generate());
    }

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/register/email-code")
    public ApiResponse<Void> sendRegisterEmailCode(
            @Valid @RequestBody SendRegisterEmailCodeRequest request,
            HttpServletRequest httpRequest) {
        captchaService.verifyAndConsume(request.captchaId(), request.captchaAnswer());
        emailVerificationService.sendRegisterCode(request.email(), extractClientIp(httpRequest));
        return new ApiResponse<>("SUCCESS", "验证码已发送", null);
    }

    @ResponseStatus(HttpStatus.CREATED)
    @PostMapping("/register")
    public ApiResponse<RegisterResponse> register(@Valid @RequestBody RegisterRequest registerRequest) {
        RegisterResponse registerResponse =
                registrationService.register(registerRequest);

        return new ApiResponse<>(
                "SUCCESS",
                "注册成功",
                registerResponse
        );
    }

    private static String extractClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            int comma = forwardedFor.indexOf(',');
            String first = (comma >= 0 ? forwardedFor.substring(0, comma) : forwardedFor).strip();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.strip();
        }
        return request.getRemoteAddr();
    }
}
