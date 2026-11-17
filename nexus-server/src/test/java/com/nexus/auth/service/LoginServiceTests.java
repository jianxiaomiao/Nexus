package com.nexus.auth.service;

import com.nexus.auth.dto.*;
import com.nexus.auth.exception.*;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LoginServiceTests {
    @Mock UserMapper users;
    @Mock PasswordEncoder passwords;
    @Mock AuthSessionService sessions;
    LoginService service;
    final LoginRequest request = new LoginRequest(" User@Example.com ", "test-password");
    @BeforeEach void setUp() { service = new LoginService(users, passwords, sessions); }
    private User user() {
        User user = new User(); user.setId(7L); user.setPasswordHash("test-stored-hash");
        user.setStatus(0); user.setIsDeleted(0); return user;
    }
    @Test void unknownUserDoesNotAttemptPasswordOrSessionCreation() {
        when(users.selectOne(any())).thenReturn(null);
        assertThrows(InvalidCredentialsException.class, () -> service.login(request));
        verifyNoInteractions(passwords, sessions);
    }
    @Test void wrongPasswordDoesNotCreateASession() {
        when(users.selectOne(any())).thenReturn(user());
        when(passwords.matches(request.password(), "test-stored-hash")).thenReturn(false);
        assertThrows(InvalidCredentialsException.class, () -> service.login(request));
        verifyNoInteractions(sessions);
    }
    @Test void deletedUserIsRejectedByTheLockedSessionCreationBoundary() {
        successfulPassword();
        when(sessions.create(7L, "test-stored-hash")).thenThrow(new InvalidCredentialsException());
        assertThrows(InvalidCredentialsException.class, () -> service.login(request));
    }
    @Test void bannedUserIsRejectedByTheLockedSessionCreationBoundary() {
        successfulPassword();
        when(sessions.create(7L, "test-stored-hash")).thenThrow(new AccountForbiddenException());
        assertThrows(AccountForbiddenException.class, () -> service.login(request));
    }
    @Test void wrongPasswordDoesNotRevealBan() {
        User user = user(); user.setStatus(1); when(users.selectOne(any())).thenReturn(user);
        when(passwords.matches(request.password(), "test-stored-hash")).thenReturn(false);
        assertThrows(InvalidCredentialsException.class, () -> service.login(request));
        verifyNoInteractions(sessions);
    }
    @Test void verifiedCredentialsDelegateToSessionCreationAndReturnBothTokens() {
        successfulPassword();
        AuthTokensResponse response = new AuthTokensResponse("test-at", "Bearer", 600, "test-rt", "9", Instant.parse("2026-11-17T00:00:00Z"));
        when(sessions.create(7L, "test-stored-hash")).thenReturn(response);
        assertSame(response, service.login(request));
        verify(sessions).create(7L, "test-stored-hash");
    }
    private void successfulPassword() {
        when(users.selectOne(any())).thenReturn(user());
        when(passwords.matches(request.password(), "test-stored-hash")).thenReturn(true);
    }
}
