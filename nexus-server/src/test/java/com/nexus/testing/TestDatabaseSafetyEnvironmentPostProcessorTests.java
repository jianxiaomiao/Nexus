package com.nexus.testing;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TestDatabaseSafetyEnvironmentPostProcessorTests {

    @Test
    void acceptsOnlyTheDedicatedMysqlSchema() {
        assertTrue(TestDatabaseSafetyEnvironmentPostProcessor.isTestDatabaseUrl(
                "jdbc:mysql://127.0.0.1:3306/nexus_test?serverTimezone=Asia/Shanghai"));
        assertFalse(TestDatabaseSafetyEnvironmentPostProcessor.isTestDatabaseUrl(
                "jdbc:mysql://127.0.0.1:3306/nexus"));
        assertFalse(TestDatabaseSafetyEnvironmentPostProcessor.isTestDatabaseUrl(
                "jdbc:mysql://127.0.0.1:3306/nexus_test_shadow"));
        assertFalse(TestDatabaseSafetyEnvironmentPostProcessor.isTestDatabaseUrl(
                "jdbc:mysql://127.0.0.1:3306/nexus?database=nexus_test"));
        assertFalse(TestDatabaseSafetyEnvironmentPostProcessor.isTestDatabaseUrl(null));
    }

    @Test
    void rejectsUnsafeTargetsWithoutPrintingTheJdbcUrl() {
        String unsafeUrl = "jdbc:mysql://127.0.0.1:3306/nexus?password=not-for-logs";
        IllegalStateException exception = assertThrows(IllegalStateException.class,
                () -> TestDatabaseSafetyEnvironmentPostProcessor.requireTestDatabase(
                        unsafeUrl, "spring.datasource.url"));
        assertFalse(exception.getMessage().contains("not-for-logs"));
        assertDoesNotThrow(() -> TestDatabaseSafetyEnvironmentPostProcessor.requireTestDatabase(
                "jdbc:mysql://127.0.0.1:3306/nexus_test", "spring.datasource.url"));
    }

    @Test
    void rejectsIndependentFlywayOrHikariOverrides() {
        TestDatabaseSafetyEnvironmentPostProcessor guard = new TestDatabaseSafetyEnvironmentPostProcessor();
        MockEnvironment flywayOverride = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/nexus_test")
                .withProperty("spring.flyway.url", "jdbc:mysql://127.0.0.1:3306/nexus");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(flywayOverride, null));

        MockEnvironment hikariOverride = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/nexus_test")
                .withProperty("spring.datasource.hikari.jdbc-url", "jdbc:mysql://127.0.0.1:3306/nexus");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(hikariOverride, null));

        MockEnvironment schemaOverride = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:mysql://127.0.0.1:3306/nexus_test")
                .withProperty("spring.flyway.schemas", "nexus");
        assertThrows(IllegalStateException.class, () -> guard.postProcessEnvironment(schemaOverride, null));
    }
}
