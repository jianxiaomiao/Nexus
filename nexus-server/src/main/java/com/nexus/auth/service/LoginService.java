package com.nexus.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.nexus.auth.dto.LoginRequest;
import com.nexus.auth.dto.LoginResponse;
import com.nexus.auth.exception.AccountForbiddenException;
import com.nexus.auth.exception.InvalidCredentialsException;
import com.nexus.auth.token.IssuedAccessToken;
import com.nexus.auth.token.JwtTokenService;
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
    private final JwtTokenService jwtTokenService;

    public LoginResponse login(LoginRequest request) {
        String normalizedEmail = request.email()
                .strip()
                .toLowerCase(Locale.ROOT);

        String rawPassword = request.password();

        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery()
                        .eq(User::getEmail, normalizedEmail)
        );

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

        // 3. 密码正确，但账号已删除
        if (Integer.valueOf(1).equals(user.getIsDeleted())) {
            throw new InvalidCredentialsException();
        }

        // 4. 密码正确，但账号被封禁
        if (Integer.valueOf(1).equals(user.getStatus())) {
            throw new AccountForbiddenException();
        }

        // 5. 所有检查通过后，才能签发 JWT
        IssuedAccessToken issuedToken =
                jwtTokenService.issueAccessToken(user.getId());

        return new LoginResponse(
                issuedToken.value(),
                "Bearer",
                issuedToken.expiresInSeconds()
        );
    }
}
