package com.nexus.auth.controller;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.AuthTokensResponse;
import jakarta.servlet.http.HttpServletResponse;
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
    @PostMapping(value = "/login", headers = "X-Nexus-Session-Transport!=cookie")
    public ApiResponse<AuthTokensResponse> login(@Valid @RequestBody LoginRequest loginRequest, HttpServletResponse response){
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        AuthTokensResponse loginResponse = loginService.login(loginRequest);
        return new ApiResponse<>(
                "SUCCESS",
                "登录成功",
                loginResponse
        );
    }
}
