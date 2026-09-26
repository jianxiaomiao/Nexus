package com.nexus.auth.web;

import com.nexus.auth.exception.InvalidAccessTokenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BearerUserIdResolverTests {

    @Mock
    private JwtDecoder jwtDecoder;

    @Mock
    private Jwt jwt;

    private BearerUserIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BearerUserIdResolver(jwtDecoder);
    }

    @Test
    void validBearerTokenShouldReturnVerifiedSubjectAsUserId() {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("42");

        assertEquals(42L, resolver.resolve("Bearer valid-token"));
        verify(jwtDecoder).decode("valid-token");
    }

    @Test
    void missingOrMalformedBearerHeaderShouldBeRejectedBeforeDecoding() {
        for (String authorization : new String[] {
                null, "", "Basic valid-token", "Bearer ", "Bearer token extra"
        }) {
            assertThrows(InvalidAccessTokenException.class,
                    () -> resolver.resolve(authorization));
        }

        verifyNoInteractions(jwtDecoder);
    }

    @Test
    void tamperedOrExpiredTokenShouldBeRejected() {
        when(jwtDecoder.decode("tampered-token"))
                .thenThrow(new JwtException("invalid signature"));
        when(jwtDecoder.decode("expired-token"))
                .thenThrow(new JwtException("expired"));

        assertThrows(InvalidAccessTokenException.class,
                () -> resolver.resolve("Bearer tampered-token"));
        assertThrows(InvalidAccessTokenException.class,
                () -> resolver.resolve("Bearer expired-token"));
    }

    @Test
    void missingNonNumericOrNonPositiveSubjectShouldBeRejected() {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);

        for (String subject : new String[] {null, "not-a-number", "0", "-1"}) {
            when(jwt.getSubject()).thenReturn(subject);
            assertThrows(InvalidAccessTokenException.class,
                    () -> resolver.resolve("Bearer valid-token"));
        }
    }
}
