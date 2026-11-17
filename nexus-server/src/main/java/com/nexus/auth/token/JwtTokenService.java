package com.nexus.auth.token;

import com.nexus.auth.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@RequiredArgsConstructor
public class JwtTokenService {

    private final JwtEncoder jwtEncoder;
    private final JwtProperties jwtProperties;
    private final Clock clock;

    public IssuedAccessToken issueAccessToken(long userId, long sessionId, Instant sessionExpiresAt) {
        return issueAccessToken(userId, sessionId, sessionExpiresAt, clock.instant());
    }

    public IssuedAccessToken issueAccessToken(long userId, long sessionId, Instant sessionExpiresAt, Instant now) {
        if (userId <= 0 || sessionId <= 0) throw new IllegalArgumentException("用户和会话ID必须为正数");
        Duration tokenTtl = jwtProperties.accessTokenTtl().compareTo(Duration.ofMinutes(30)) > 0
                ? Duration.ofMinutes(30) : jwtProperties.accessTokenTtl();
        Instant requestedExpiry = now.plus(tokenTtl);
        Instant expiresAt = (requestedExpiry.isBefore(sessionExpiresAt) ? requestedExpiry : sessionExpiresAt)
                .truncatedTo(ChronoUnit.SECONDS);
        long remainingSeconds = Duration.between(now, expiresAt).toSeconds();
        if (remainingSeconds <= 0) throw new IllegalArgumentException("剩余寿命不足以签发AT");
        Instant issuedAt = now.truncatedTo(ChronoUnit.SECONDS);

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.issuer())
                .subject(String.valueOf(userId))
                .claim("sid", String.valueOf(sessionId))
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .build();

        JwsHeader header = JwsHeader
                .with(MacAlgorithm.HS256)
                .type("JWT")
                .build();

        Jwt jwt = jwtEncoder.encode(
                JwtEncoderParameters.from(header, claims)
        );

        return new IssuedAccessToken(
                jwt.getTokenValue(),
                remainingSeconds
        );
    }
}
