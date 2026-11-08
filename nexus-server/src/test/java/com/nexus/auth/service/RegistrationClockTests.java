package com.nexus.auth.service;

import com.nexus.auth.dto.RegisterRequest;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class RegistrationClockTests {
    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Asia/Shanghai", "America/Los_Angeles"})
    void registrationStoresOneUtcMillisecondInstantRegardlessOfClockZone(String zone) {
        UserMapper users = mock(UserMapper.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        Clock clock = spy(Clock.fixed(Instant.parse("2026-11-08T20:30:00.123456789Z"), ZoneId.of(zone)));
        when(encoder.encode("test-only-password")).thenReturn("test-only-encoded-value");
        when(users.insert(any(User.class))).thenAnswer(call -> {
            User user = call.getArgument(0);
            assertEquals(LocalDateTime.parse("2026-11-08T20:30:00.123"), user.getCreatedAt());
            assertEquals(user.getCreatedAt(), user.getUpdatedAt());
            return 1;
        });
        new RegistrationService(users, encoder, clock).register(
                new RegisterRequest("clock@example.com", "clock", "test-only-password"));
        verify(users).insert(any(User.class));
        verify(clock).instant();
    }
}
