package com.nexus.auth.web;

import com.nexus.auth.exception.InvalidAccessTokenException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class BearerUserIdResolver {
    private final JwtDecoder jwtDecoder;

    public long resolve(String authorization) {
        if (authorization == null
                || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new InvalidAccessTokenException();
        }

        String token = authorization.substring(7);
        if (token.isBlank() || token.chars().anyMatch(Character::isWhitespace)) {
            throw new InvalidAccessTokenException();
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token); // 验签并执行配置的时间、issuer 校验
        } catch (JwtException exception) {
            throw new InvalidAccessTokenException();
        }

        try {
            long userId = Long.parseLong(jwt.getSubject());
            if (userId <= 0) throw new InvalidAccessTokenException();
            return userId;
        } catch (NumberFormatException exception) {
            throw new InvalidAccessTokenException();
        }
    }
}
