package com.nexus.auth.websession;

import com.nexus.auth.dto.AuthTokensResponse;
import java.time.Instant;

/** Explicit public projection: never add the refresh credential to this DTO. */
public record WebTokensResponse(String accessToken, String tokenType, long expiresInSeconds,
                                String sessionId, Instant sessionExpiresAt) {
    public static WebTokensResponse from(AuthTokensResponse tokens) {
        return new WebTokensResponse(tokens.accessToken(), tokens.tokenType(), tokens.expiresInSeconds(),
                tokens.sessionId(), tokens.sessionExpiresAt());
    }
    @Override public String toString() { return "WebTokensResponse[credentials omitted]"; }
}
