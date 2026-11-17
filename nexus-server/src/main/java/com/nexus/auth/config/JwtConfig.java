package com.nexus.auth.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;

//Spring 配置类，关闭 CGLIB 代理
@Configuration(proxyBeanMethods = false)
//启用配置绑定，把 yml / 环境变量里的配置自动注入到JwtProperties对象。
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfig {

    @Bean
    public Clock jwtClock() {
        return Clock.systemUTC();
    }

    @Bean
    public SecretKey jwtSigningKey(JwtProperties properties) {
        byte[] keyBytes;

        try {
            keyBytes = Base64.getDecoder().decode(properties.secret());
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(
                    "JWT secret 必须是有效的 Base64 字符串",
                    exception
            );
        }

        if (keyBytes.length < 32) {
            throw new IllegalStateException(
                    "HS256 JWT secret 至少需要 32 个字节"
            );
        }

        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    //JwtEncoder 作用：生成（签发）JWT 令牌，对 JWT 进行签名。
    //配套的 JwtDecoder 是用来解析、校验 JWT（验证签名是否合法、有没有篡改）。
    @Bean
    public JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder
                .withSecretKey(jwtSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    @Primary
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();

        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                timestamps, new JwtIssuerValidator(properties.issuer())));
        return decoder;
    }

    /** 只供撤销本会话使用：忽略exp，保留验签、issuer及尚未生效时间检查。 */
    @Bean("logoutJwtDecoder")
    public JwtDecoder logoutJwtDecoder(SecretKey jwtSigningKey, JwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256).build();
        OAuth2TokenValidator<Jwt> notBefore = jwt -> jwt.getNotBefore() != null
                && clock.instant().isBefore(jwt.getNotBefore())
                ? OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"))
                : OAuth2TokenValidatorResult.success();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                new JwtIssuerValidator(properties.issuer()), notBefore));
        return decoder;
    }
}
