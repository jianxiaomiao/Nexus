package com.nexus.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtPropertiesTests {
    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner()
                    .withUserConfiguration(TestConfig.class)
                    .withPropertyValues(
                            "nexus.auth.jwt.issuer=nexus",
                            "nexus.auth.jwt.access-token-ttl=30m",
                            "nexus.auth.jwt.secret=test-secret"
                    );

    @Test
    void shouldBindJwtProperties() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());

            JwtProperties properties =
                    context.getBean(JwtProperties.class);

            assertEquals("nexus", properties.issuer());
            assertEquals(
                    Duration.ofMinutes(30),
                    properties.accessTokenTtl()
            );
            assertEquals(
                    "test-secret",
                    properties.secret()
            );
        });
    }


    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(JwtProperties.class)
    static class TestConfig {
    }
}
