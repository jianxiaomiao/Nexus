package com.nexus.auth.controller;

import com.nexus.auth.dto.*;
import com.nexus.auth.service.*;
import com.nexus.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthSessionController {
    private final AuthSessionService sessions;
    private final LogoutService logout;

    @PostMapping(value = "/refresh", headers = "X-Nexus-Session-Transport!=cookie")
    public ApiResponse<AuthTokensResponse> refresh(@Valid @RequestBody RefreshRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        return new ApiResponse<>("SUCCESS", "刷新成功", sessions.refresh(request.refreshToken()));
    }

    @PostMapping(value = "/logout", headers = "X-Nexus-Session-Transport!=cookie")
    public ApiResponse<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization,
                                    HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        logout.logout(authorization);
        return new ApiResponse<>("SUCCESS", "退出成功", null);
    }
}
