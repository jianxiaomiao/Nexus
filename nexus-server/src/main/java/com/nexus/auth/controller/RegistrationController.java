package com.nexus.auth.controller;

import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.RegisterResponse;
import com.nexus.auth.dto.SendRegisterEmailCodeRequest;
import com.nexus.auth.service.EmailVerificationService;
import com.nexus.auth.service.RegistrationService;
import com.nexus.common.web.ApiResponse;
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

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/register/email-code")
    public ApiResponse<Void> sendRegisterEmailCode(@Valid @RequestBody SendRegisterEmailCodeRequest request) {
        emailVerificationService.sendRegisterCode(request.email());
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
}
