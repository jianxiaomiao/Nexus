package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.RegisterResponse;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RegistrationServiceIntegrationTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private EmailVerificationService emailVerificationService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private EmailSender emailSender;

    private String issueCode(String email) {
        AtomicReference<String> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            captured.set(invocation.getArgument(1));
            return null;
        }).when(emailSender).sendVerificationCode(eq(email), org.mockito.ArgumentMatchers.anyString(), anyInt());
        emailVerificationService.sendRegisterCode(email);
        return captured.get();
    }

    @Test
    void registerShouldCreateUser() {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        String code = issueCode(email);
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "testUser",
                "testPassword",
                code
        );

        RegisterResponse registerResponse = registrationService.register(registerRequest);
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, email)
        );

        assertNotNull(user);
        assertAll(
                () -> assertNotNull(registerResponse),
                () -> assertEquals(email, registerResponse.email()),
                () -> assertEquals(email, user.getEmail()),
                () -> assertEquals("testUser", registerResponse.displayName()),
                () -> assertEquals("testUser", user.getDisplayName()),
                () -> assertTrue(passwordEncoder.matches("testPassword", user.getPasswordHash())),
                () -> assertNotNull(user.getEmailVerifiedAt()),
                () -> assertEquals("USER", user.getRole()),
                () -> assertEquals(0, user.getStatus()),
                () -> assertEquals(0, user.getIsDeleted()),
                () -> assertNull(user.getDeletedAt()),
                () -> assertNotNull(user.getCreatedAt()),
                () -> assertNotNull(user.getUpdatedAt())
        );
    }

    @Test
    void registerShouldStoreEncodedPassword() {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        String code = issueCode(email);
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "testUser",
                "testPassword",
                code
        );

        registrationService.register(registerRequest);
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, email)
        );

        assertNotNull(user);
        assertAll(
                () -> assertNotEquals("testPassword", user.getPasswordHash()),
                () -> assertTrue(passwordEncoder.matches("testPassword", user.getPasswordHash())),
                () -> assertFalse(passwordEncoder.matches("NottestPassword", user.getPasswordHash()))
        );
    }

    @Test
    void registerShouldRejectDuplicateEmail() {
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        String code = issueCode(email);
        RegisterRequest firstRegisterRequest = new RegisterRequest(
                email,
                "firstTestUser",
                "testPassword",
                code
        );

        RegisterRequest secondRegisterRequest = new RegisterRequest(
                email,
                "secondTestUser",
                "testPassword",
                code
        );

        registrationService.register(firstRegisterRequest);

        assertThrows(
                EmailAlreadyRegisteredException.class,
                () -> registrationService.register(secondRegisterRequest)
        );
    }
}
