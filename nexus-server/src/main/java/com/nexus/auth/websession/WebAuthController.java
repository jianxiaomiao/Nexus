package com.nexus.auth.websession;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.exception.InvalidRefreshTokenException;
import com.nexus.auth.service.AuthSessionService;
import com.nexus.auth.service.LoginService;
import com.nexus.common.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping(value = "/api/auth", headers = "X-Nexus-Session-Transport=cookie")
@RequiredArgsConstructor
public class WebAuthController {
    private final WebSessionPolicy policy;
    private final LoginService login;
    private final AuthSessionService sessions;
    private final WebLogoutService logout;

    @PostMapping(value = "/login", consumes = "application/json")
    public ApiResponse<WebTokensResponse> login(@Valid @RequestBody LoginRequest body,
            HttpServletRequest request, HttpServletResponse response) {
        policy.check(request);
        noStore(response);
        var tokens = login.login(body);
        policy.set(response, tokens.refreshToken());
        return new ApiResponse<>("SUCCESS", "登录成功", WebTokensResponse.from(tokens));
    }

    @PostMapping(value = "/refresh", consumes = "application/json")
    public ApiResponse<WebTokensResponse> refresh(@RequestBody Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        policy.check(request);
        noStore(response);
        String expected = expectedSession(body);
        String token = policy.token(request);
        try {
            if (token == null) throw new InvalidRefreshTokenException();
            var tokens = sessions.refreshForWeb(token, expected);
            policy.set(response, tokens.refreshToken());
            return new ApiResponse<>("SUCCESS", "刷新成功", WebTokensResponse.from(tokens));
        } catch (RuntimeException exception) { policy.clear(response); throw exception; }
    }

    @PostMapping(value = "/logout", consumes = "application/json")
    public ApiResponse<Map<String, String>> logout(@RequestBody Map<String, Object> body,
            HttpServletRequest request, HttpServletResponse response) {
        policy.check(request);
        noStore(response);
        String expected = expectedSession(body);
        String token = policy.token(request);
        try {
            if (token != null) logout.logout(token, expected);
            return new ApiResponse<>("SUCCESS", "退出成功", Map.of("serverRevocation", token == null ? "not-needed" : "confirmed"));
        } finally { policy.clear(response); }
    }

    private String expectedSession(Map<String, Object> body) {
        if (body.keySet().stream().anyMatch(key -> !"expectedSessionId".equals(key))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WEB_SESSION_REQUEST");
        }
        Object value = body.get("expectedSessionId");
        if (value == null) return null;
        if (!(value instanceof String text) || !text.matches("[1-9][0-9]{0,18}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_WEB_SESSION_REQUEST");
        }
        return text;
    }
    private void noStore(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ApiResponse<Void> rejected(ResponseStatusException exception, HttpServletResponse response) {
        response.setStatus(exception.getStatusCode().value());
        response.setHeader("Cache-Control", "no-store");
        return new ApiResponse<>(exception.getReason(), "网页会话请求未被接受", null);
    }
}
