package com.nexus.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class AuthSessionPropertiesTests {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AuthSessionConfig.class);

    @Test
    void shouldDefaultToTwentyFourHoursWithoutAnExplicitSetting() {
        contextRunner.run(context -> {
            assertNull(context.getStartupFailure());
            assertEquals(Duration.ofHours(24),
                    context.getBean(AuthSessionProperties.class).absoluteTtl());
        });
    }

    @Test
    void shouldAllowAnExplicitDurationToOverrideTheDefault() {
        contextRunner.withPropertyValues("nexus.auth.session.absolute-ttl=48h")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    assertEquals(Duration.ofHours(48),
                            context.getBean(AuthSessionProperties.class).absoluteTtl());
                });
    }

    @Test
    void shouldRejectAZeroLifetimeAtStartup() {
        assertInvalidProperty("0s");
    }

    @Test
    void shouldRejectANegativeLifetimeAtStartup() {
        assertInvalidProperty("-1h");
    }

    @Test
    void shouldRejectAMalformedLifetimeAtStartup() {
        assertInvalidProperty("not-a-duration");
    }

    @Test
    void shouldRejectANullLifetimeOnDirectConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new AuthSessionProperties(null));
    }

    private void assertInvalidProperty(String value) {
        contextRunner.withPropertyValues("nexus.auth.session.absolute-ttl=" + value)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }
}
