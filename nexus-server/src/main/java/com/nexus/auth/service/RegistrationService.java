package com.nexus.auth.service;

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

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class RegistrationService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public RegisterResponse register(RegisterRequest request) {
        String normalizedEmail = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        User user = new User();
        user.setEmail(normalizedEmail);
        user.setDisplayName(request.displayName().strip());
        user.setPasswordHash(passwordEncoder.encode(request.password()));

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