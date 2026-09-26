package com.nexus.auth.token;

import com.nexus.auth.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JwtTokenServiceTests {

    //带时区的时间戳，jwt推荐
    private static final Instant FIXED_NOW =
            Instant.parse("2026-09-21T08:00:00Z");

    private static final Duration TOKEN_TTL =
            Duration.ofMinutes(30);

    private JwtTokenService jwtTokenService;
    private NimbusJwtDecoder jwtDecoder;

    @BeforeEach
    void setUp() {
        byte[] keyBytes =
                "0123456789abcdef0123456789abcdef"
                        .getBytes(StandardCharsets.UTF_8);

        SecretKey signingKey =
                new SecretKeySpec(keyBytes, "HmacSHA256");

        JwtEncoder jwtEncoder = NimbusJwtEncoder
                .withSecretKey(signingKey)
                .algorithm(MacAlgorithm.HS256)
                .build();

        JwtProperties jwtProperties = new JwtProperties(
                "nexus",
                TOKEN_TTL,
                Base64.getEncoder().encodeToString(keyBytes)
        );

        //冻结时间
        Clock fixedClock = Clock.fixed(
                FIXED_NOW,
                ZoneOffset.UTC
        );

        jwtTokenService = new JwtTokenService(
                jwtEncoder,
                jwtProperties,
                fixedClock
        );

        jwtDecoder = NimbusJwtDecoder
                .withSecretKey(signingKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        //时间验证
        JwtTimestampValidator timestampValidator =
                new JwtTimestampValidator(Duration.ZERO);

        timestampValidator.setClock(fixedClock);
        jwtDecoder.setJwtValidator(timestampValidator);
    }

    @Test
    void shouldIssueAccessTokenWithExpectedClaims() {
        long userId = 42L;

        IssuedAccessToken issuedToken =
                jwtTokenService.issueAccessToken(userId);

        Jwt decodedJwt =
                jwtDecoder.decode(issuedToken.value());

        assertAll(
                () -> assertFalse(issuedToken.value().isBlank()),

                () -> assertEquals(
                        1800L,
                        issuedToken.expiresInSeconds()
                ),

                () -> assertEquals(
                        "42",
                        decodedJwt.getSubject()
                ),

                () -> assertEquals(
                        "nexus",
                        decodedJwt.getClaimAsString("iss")
                ),

                () -> assertEquals(
                        FIXED_NOW,
                        decodedJwt.getIssuedAt()
                ),

                () -> assertEquals(
                        FIXED_NOW.plus(TOKEN_TTL),
                        decodedJwt.getExpiresAt()
                ),

                () -> assertEquals(
                        "HS256",
                        decodedJwt.getHeaders().get("alg")
                ),

                () -> assertEquals(
                        "JWT",
                        decodedJwt.getHeaders().get("typ")
                )
        );
    }
}