package com.nexus.auth.controller;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.LoginResponse;
import com.nexus.auth.service.LoginService;
import com.nexus.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/auth")
public class LoginController {

    private final LoginService loginService;

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest loginRequest){
        LoginResponse loginResponse = loginService.login(loginRequest);
        return new ApiResponse<LoginResponse>(
                "SUCCESS",
                "登录成功",
                loginResponse
        );
    }
}
