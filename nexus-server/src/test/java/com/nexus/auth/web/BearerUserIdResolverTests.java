package com.nexus.auth.web;

import com.nexus.auth.exception.InvalidAccessTokenException;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
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
    private UserMapper userMapper;

    @Mock
    private Jwt jwt;

    private BearerUserIdResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new BearerUserIdResolver(jwtDecoder, userMapper);
    }

    @Test
    void validBearerTokenShouldReturnVerifiedSubjectAsUserId() {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("42");
        when(userMapper.selectById(42L)).thenReturn(activeUser());

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

        verifyNoInteractions(jwtDecoder, userMapper);
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

    @Test
    void missingOrDeletedAccountInvalidatesPreviouslyIssuedJwt() {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("42");
        when(userMapper.selectById(42L)).thenReturn(null);
        assertThrows(InvalidAccessTokenException.class,
                () -> resolver.resolve("Bearer valid-token"));

        User deleted = activeUser();
        deleted.setIsDeleted(1);
        when(userMapper.selectById(42L)).thenReturn(deleted);
        assertThrows(InvalidAccessTokenException.class,
                () -> resolver.resolve("Bearer valid-token"));
    }

    @Test
    void banningAndUnbanningAccountAffectsExistingJwtImmediately() {
        when(jwtDecoder.decode("valid-token")).thenReturn(jwt);
        when(jwt.getSubject()).thenReturn("42");
        User user = activeUser();
        user.setStatus(1);
        when(userMapper.selectById(42L)).thenReturn(user);

        assertThrows(AccountForbiddenException.class,
                () -> resolver.resolve("Bearer valid-token"));
        user.setStatus(0);
        assertEquals(42L, resolver.resolve("Bearer valid-token"));
    }

    private User activeUser() {
        User user = new User();
        user.setId(42L);
        user.setStatus(0);
        user.setIsDeleted(0);
        return user;
    }
}
