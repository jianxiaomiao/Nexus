package com.nexus.auth.dto;

import com.nexus.auth.token.IssuedAccessToken;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuthTokensResponseTests {
    private static final String ACCESS_TOKEN = "test-only-access-token";
    private static final String REFRESH_TOKEN = "test-only-refresh-token";
    private static final String SESSION_ID = "9007199254740993";
    private static final Instant SESSION_EXPIRES_AT = Instant.parse("2026-11-16T01:00:00Z");

    @Test
    void shouldPreserveActualAccessLifetimeAndTheFixedSessionDeadline() {
        AuthTokensResponse response = AuthTokensResponse.from(
                new IssuedAccessToken(ACCESS_TOKEN, 600), REFRESH_TOKEN,
                SESSION_ID, SESSION_EXPIRES_AT);

        assertAll(
                () -> assertEquals(ACCESS_TOKEN, response.accessToken()),
                () -> assertEquals("Bearer", response.tokenType()),
                () -> assertEquals(600, response.expiresInSeconds()),
                () -> assertEquals(REFRESH_TOKEN, response.refreshToken()),
                () -> assertEquals(SESSION_ID, response.sessionId()),
                () -> assertEquals(SESSION_EXPIRES_AT, response.sessionExpiresAt())
        );
    }

    @Test
    void shouldRejectAPartialCredentialPair() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> AuthTokensResponse.from(null, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(null, "Bearer", 600, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(" ", "Bearer", 600, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 600, null, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 600, " ", SESSION_ID, SESSION_EXPIRES_AT))
        );
    }

    @Test
    void shouldRejectInvalidTokenAndSessionMetadata() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Other", 600, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 0, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", -1, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 600, REFRESH_TOKEN, null, SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 600, REFRESH_TOKEN, " ", SESSION_EXPIRES_AT)),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> response(ACCESS_TOKEN, "Bearer", 600, REFRESH_TOKEN, SESSION_ID, null))
        );
    }

    @Test
    void diagnosticTextShouldNotExposeCredentials() {
        AuthTokensResponse response = AuthTokensResponse.from(
                new IssuedAccessToken(ACCESS_TOKEN, 600), REFRESH_TOKEN,
                SESSION_ID, SESSION_EXPIRES_AT);
        IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                () -> response(ACCESS_TOKEN, "Other", 600, REFRESH_TOKEN, SESSION_ID, SESSION_EXPIRES_AT));

        for (String diagnostic : new String[]{response.toString(),
                new RefreshRequest(REFRESH_TOKEN).toString(), invalid.toString()}) {
            assertFalse(diagnostic.contains(ACCESS_TOKEN));
            assertFalse(diagnostic.contains(REFRESH_TOKEN));
        }
    }

    private static AuthTokensResponse response(String accessToken, String tokenType, long expiresInSeconds,
                                               String refreshToken, String sessionId, Instant sessionExpiresAt) {
        return new AuthTokensResponse(accessToken, tokenType, expiresInSeconds,
                refreshToken, sessionId, sessionExpiresAt);
    }
}
