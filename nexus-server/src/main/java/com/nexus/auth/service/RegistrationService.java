package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.dto.RegisterRequest;
import com.nexus.auth.dto.RegisterResponse;
import com.nexus.auth.exception.EmailAlreadyRegisteredException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final EmailVerificationService emailVerificationService;
    private final Clock clock;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);
        if (userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getEmail, normalizedEmail)) > 0) {
            throw new EmailAlreadyRegisteredException(new IllegalStateException("email already exists"));
        }
        LocalDateTime now = LocalDateTime.ofInstant(
                clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);

        emailVerificationService.verifyAndConsumeRegisterCode(
                normalizedEmail, request.verificationCode(), now);

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setDisplayName(request.displayName().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setEmailVerifiedAt(now);
        user.setRole("USER");
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new EmailAlreadyRegisteredException(exception);
        }

        return new RegisterResponse(
                user.getEmail(),
                user.getDisplayName()
        );
    }
}
