package com.nexus.common.logging;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoggingConfigurationTests {
    @Test
    void defaultLogLocationAndDailyRetentionAreConfigured() throws Exception {
        var source = new YamlPropertySourceLoader()
                .load("application", new ClassPathResource("application.yml"))
                .getFirst();

        assertEquals("${NEXUS_LOG_DIR:../logger}/nexus.log", source.getProperty("logging.file.name"));
        assertTrue(source.getProperty("logging.logback.rollingpolicy.file-name-pattern")
                .toString().contains("%d{yyyy-MM-dd}"));
        assertEquals("10MB", source.getProperty("logging.logback.rollingpolicy.max-file-size"));
        assertEquals(30, source.getProperty("logging.logback.rollingpolicy.max-history"));
    }
}
