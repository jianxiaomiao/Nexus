package com.nexus.testing;

import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

import java.net.URI;

/**
 * Test-classpath-only guard: fail before Flyway or Hikari can connect to a non-test database.
 */
@Order(Ordered.LOWEST_PRECEDENCE)
public final class TestDatabaseSafetyEnvironmentPostProcessor implements EnvironmentPostProcessor {
    private static final String TEST_SCHEMA = "nexus_test";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        requireTestDatabase(environment.getProperty("spring.datasource.url"), "spring.datasource.url");
        requireTestDatabaseIfPresent(environment.getProperty("spring.datasource.hikari.jdbc-url"),
                "spring.datasource.hikari.jdbc-url");
        requireTestDatabaseIfPresent(environment.getProperty("spring.flyway.url"), "spring.flyway.url");
        requireTestSchemaIfPresent(environment.getProperty("spring.flyway.default-schema"),
                "spring.flyway.default-schema");
        requireTestSchemaIfPresent(environment.getProperty("spring.flyway.schemas"), "spring.flyway.schemas");
    }

    static void requireTestDatabase(String url, String property) {
        if (!isTestDatabaseUrl(url)) {
            throw new IllegalStateException("Unsafe test database: " + property
                    + " must point to the dedicated nexus_test schema");
        }
    }

    private static void requireTestDatabaseIfPresent(String url, String property) {
        if (url != null && !url.isBlank()) {
            requireTestDatabase(url, property);
        }
    }

    private static void requireTestSchemaIfPresent(String schema, String property) {
        if (schema != null && !schema.isBlank() && !TEST_SCHEMA.equals(schema)) {
            throw new IllegalStateException("Unsafe test database: " + property
                    + " must be nexus_test");
        }
    }

    static boolean isTestDatabaseUrl(String url) {
        if (url == null || !url.startsWith("jdbc:mysql://")) {
            return false;
        }
        try {
            URI uri = URI.create(url.substring("jdbc:".length()));
            return "mysql".equals(uri.getScheme())
                    && uri.getRawAuthority() != null
                    && "/nexus_test".equals(uri.getRawPath());
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
