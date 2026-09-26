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
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class RegistrationServiceIntegrationTests {

    @Autowired
    private RegistrationService registrationService;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void registerShouldCreateUser() {
        // Arrange
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "testUser",
                "testPassword"
        );

        // Act
        RegisterResponse registerResponse =registrationService.register(registerRequest);
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, email)
        );

        // Assert
        assertNotNull(user);
        assertAll(
                ()->assertNotNull(registerResponse),
                ()->assertEquals(email, registerResponse.email()),
                ()->assertEquals(email, user.getEmail()),
                ()->assertEquals("testUser", registerResponse.displayName()),
                ()->assertEquals("testUser", user.getDisplayName()),
                ()->assertTrue(passwordEncoder.matches("testPassword", user.getPasswordHash())),
                ()->assertNull(user.getEmailVerifiedAt()),
                ()->assertEquals(0, user.getStatus()),
                ()->assertEquals(0, user.getIsDeleted()),
                ()->assertNull(user.getDeletedAt()),
                ()->assertNotNull(user.getCreatedAt()),
                ()->assertNotNull(user.getUpdatedAt())
        );
    }

    @Test
    void registerShouldStoreEncodedPassword() {
        // Arrange
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        RegisterRequest registerRequest = new RegisterRequest(
                email,
                "testUser",
                "testPassword"
        );

        // Act
        registrationService.register(registerRequest);
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, email)
        );

        // Assert
        assertNotNull(user);
        assertAll(
                ()->assertNotEquals("testPassword", user.getPasswordHash()),
                ()->assertTrue(passwordEncoder.matches("testPassword", user.getPasswordHash())),
                ()->assertFalse(passwordEncoder.matches("NottestPassword", user.getPasswordHash()))
        );
    }

    @Test
    void registerShouldRejectDuplicateEmail() {
        // Arrange
        String email = "registration-" + UUID.randomUUID() + "@example.com";
        RegisterRequest firstRegisterRequest = new RegisterRequest(
                email,
                "firstTestUser",
                "testPassword"
        );

        RegisterRequest secondRegisterRequest = new RegisterRequest(
                email,
                "secondTestUser",
                "testPassword"
        );

        // Act
        registrationService.register(firstRegisterRequest);

        // Assert
        assertThrows(
                EmailAlreadyRegisteredException.class,
                ()->registrationService.register(secondRegisterRequest)
        );
    }
}