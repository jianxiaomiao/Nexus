package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.AuthTokensResponse;
import com.nexus.auth.exception.InvalidCredentialsException;
import com.nexus.auth.exception.AuthSessionUnavailableException;
import org.springframework.dao.DataAccessException;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
@RequiredArgsConstructor
public class LoginService {
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final AuthSessionService sessions;

    public AuthTokensResponse login(LoginRequest request) {
        AuthSessionTransactions.requireNoOuterTransaction();
        String normalizedEmail = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        String rawPassword = request.password();

        User user;
        try {
            user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, normalizedEmail)
            );
        } catch (DataAccessException exception) {
            throw new AuthSessionUnavailableException(exception);
        }

        // 1. 用户不存在
        if (user == null) {
            throw new InvalidCredentialsException();
        }

        // 2. 密码错误
        boolean passwordMatches = passwordEncoder.matches(
                rawPassword,
                user.getPasswordHash()
        );

        if (!passwordMatches) {
            throw new InvalidCredentialsException();
        }

        // 当前账号状态与并发禁用的互斥边界在User行锁内检查。
        return sessions.create(user.getId(), user.getPasswordHash());
    }
}
