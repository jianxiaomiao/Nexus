package com.nexus.auth.service;

import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.LoginResponse;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.InvalidCredentialsException;
import com.nexus.auth.token.IssuedAccessToken;
import com.nexus.auth.token.JwtTokenService;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTests {

    private static final String EMAIL = "test-user@example.com";
    private static final String RAW_PASSWORD = "true-password";
    private static final String WRONG_PASSWORD = "wrong-password";
    private static final String STORED_PASSWORD_HASH = "stored-password-hash";

    @Mock
    private UserMapper userMapper;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtTokenService jwtTokenService;

    private LoginService loginService;

    @BeforeEach
    void setUp() {
        loginService = new LoginService(
                userMapper,
                passwordEncoder,
                jwtTokenService
        );
    }

    @Test
    void shouldRejectWhenUserDoesNotExist() {
        LoginRequest request = new LoginRequest(
                "missing@example.com",
                "test-password"
        );

        when(userMapper.selectOne(any()))
                .thenReturn(null);

        InvalidCredentialsException exception = assertThrows(
                InvalidCredentialsException.class,
                () -> loginService.login(request)
        );

        assertEquals(
                "邮箱或密码错误",
                exception.getMessage()
        );

        verifyNoInteractions(
                passwordEncoder,
                jwtTokenService
        );
    }

    @Test
    void shouldRejectWhenPasswordIsIncorrect() {
        LoginRequest request = new LoginRequest(
                EMAIL,
                WRONG_PASSWORD
        );

        User user = createActiveUser();

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), STORED_PASSWORD_HASH))
                .thenReturn(false);

        InvalidCredentialsException exception = assertThrows(
                InvalidCredentialsException.class,
                () -> loginService.login(request)
        );

        assertEquals(
                "邮箱或密码错误",
                exception.getMessage()
        );

        verifyNoInteractions(
                jwtTokenService
        );
    }

    @Test
    void shouldRejectWhenUserIsDeleted() {
        LoginRequest request = new LoginRequest(
                EMAIL,
                RAW_PASSWORD
        );

        User user = createActiveUser();
        user.setIsDeleted(1);

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), STORED_PASSWORD_HASH))
                .thenReturn(true);

        InvalidCredentialsException exception = assertThrows(
                InvalidCredentialsException.class,
                () -> loginService.login(request)
        );

        assertEquals(
                "邮箱或密码错误",
                exception.getMessage()
        );

        verifyNoInteractions(
                jwtTokenService
        );
    }

    @Test
    void shouldRejectWhenUserIsBanned() {
        LoginRequest request = new LoginRequest(
                EMAIL,
                RAW_PASSWORD
        );

        User user = createActiveUser();
        user.setStatus(1);

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), STORED_PASSWORD_HASH))
                .thenReturn(true);

        AccountForbiddenException exception = assertThrows(
                AccountForbiddenException.class,
                () -> loginService.login(request)
        );

        assertEquals(
                "账号已封禁",
                exception.getMessage()
        );

        verifyNoInteractions(
                jwtTokenService
        );
    }

    @Test
    void shouldNotRevealBanWhenPasswordIsIncorrect() {
        LoginRequest request = new LoginRequest(
                EMAIL,
                WRONG_PASSWORD
        );

        User user = createActiveUser();
        user.setStatus(1);

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), STORED_PASSWORD_HASH))
                .thenReturn(false);

        InvalidCredentialsException exception = assertThrows(
                InvalidCredentialsException.class,
                () -> loginService.login(request)
        );

        assertEquals("邮箱或密码错误", exception.getMessage());
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void shouldReturnLoginResponseWhenCredentialsAreValid() {
        LoginRequest request = new LoginRequest(
                EMAIL,
                RAW_PASSWORD
        );

        User user = createActiveUser();

        when(userMapper.selectOne(any())).thenReturn(user);
        when(passwordEncoder.matches(request.password(), STORED_PASSWORD_HASH))
                .thenReturn(true);
        when(jwtTokenService.issueAccessToken(user.getId()))
                .thenReturn(new IssuedAccessToken("test-issued-token", 1800L));

        LoginResponse loginResponse = loginService.login(request);

        assertNotNull(loginResponse);
        assertAll(
                () -> assertEquals("Bearer", loginResponse.tokenType()),
                () -> assertEquals("test-issued-token", loginResponse.accessToken()),
                () -> assertEquals(1800L, loginResponse.expiresInSeconds())
        );

        verify(passwordEncoder).matches(RAW_PASSWORD, STORED_PASSWORD_HASH);
        verify(jwtTokenService).issueAccessToken(1L);
    }

    private User createActiveUser() {
        User user = new User();
        user.setId(1L);
        user.setEmail(EMAIL);
        user.setPasswordHash(STORED_PASSWORD_HASH);
        user.setStatus(0);
        user.setIsDeleted(0);
        return user;
    }
}
