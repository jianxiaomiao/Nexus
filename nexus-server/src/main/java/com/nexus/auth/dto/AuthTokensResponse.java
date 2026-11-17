package com.nexus.auth.dto;

import com.nexus.auth.token.IssuedAccessToken;

import java.time.Instant;

/**
 * 会话登录和刷新共用的成功响应。仅在会话事务提交后返回。
 * 桌面 Main 必须保存并剔除 RT 后再将会话信息交给 Renderer。
 */
public record AuthTokensResponse(
        String accessToken,
        String tokenType,
        long expiresInSeconds,
        String refreshToken,
        String sessionId,
        Instant sessionExpiresAt
) {
    public AuthTokensResponse {
        if (accessToken == null || accessToken.isBlank()
                || refreshToken == null || refreshToken.isBlank()) {
            throw new IllegalArgumentException("成功响应必须包含非空 AT 和 RT");
        }
        if (!"Bearer".equals(tokenType)) {
            throw new IllegalArgumentException("访问令牌类型必须为 Bearer");
        }
        if (expiresInSeconds <= 0) {
            throw new IllegalArgumentException("AT 有效秒数必须为正数");
        }
        if (sessionId == null || sessionId.isBlank() || sessionExpiresAt == null) {
            throw new IllegalArgumentException("成功响应必须包含会话 ID 和绝对到期时间");
        }
    }

    public static AuthTokensResponse from(IssuedAccessToken accessToken, String refreshToken,
                                          String sessionId, Instant sessionExpiresAt) {
        if (accessToken == null) {
            throw new IllegalArgumentException("成功响应必须包含 AT");
        }
        return new AuthTokensResponse(accessToken.value(), "Bearer", accessToken.expiresInSeconds(),
                refreshToken, sessionId, sessionExpiresAt);
    }

    @Override
    public String toString() {
        return "AuthTokensResponse[tokenType=" + tokenType
                + ", expiresInSeconds=" + expiresInSeconds
                + ", sessionExpiresAt=" + sessionExpiresAt + "]";
    }
}
