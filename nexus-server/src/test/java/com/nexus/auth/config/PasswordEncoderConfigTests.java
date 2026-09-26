package com.nexus.auth.config;

import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

import static org.junit.jupiter.api.Assertions.*;

@Slf4j
@SpringJUnitConfig(PasswordEncoderConfig.class)
class PasswordEncoderConfigTests {

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void shouldEncodeAndMatchPassword() {
        // 仅供测试使用的虚拟密码
        String rawPassword = "Nexus-test-password-123!";

        String firstHash = passwordEncoder.encode(rawPassword);
        String secondHash = passwordEncoder.encode(rawPassword);

        log.info("第一次生成的哈希：{}", firstHash);
        log.info("第二次生成的哈希：{}", secondHash);

        assertAll(
                () -> assertNotEquals(rawPassword, firstHash),
                () -> assertNotEquals(firstHash, secondHash),
                () -> assertTrue(passwordEncoder.matches(rawPassword, firstHash)),
                () -> assertTrue(passwordEncoder.matches(rawPassword, secondHash)),
                () -> assertFalse(
                        passwordEncoder.matches("wrong-password", firstHash)
                )
        );
    }
}