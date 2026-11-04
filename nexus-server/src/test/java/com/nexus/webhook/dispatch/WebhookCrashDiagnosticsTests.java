package com.nexus.webhook.dispatch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WebhookCrashDiagnosticsTests {
    @Test
    void databaseGuardRejectsWrongVersionSchemaAndAccountBeforeMigration() {
        assertDoesNotThrow(() -> WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase("nexus_test", "nexus_test_user@localhost", "8.4.13"));
        assertDoesNotThrow(() -> WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase("nexus_test", "nexus_test_user@%", "8.4.13-commercial"));
        for (String version : List.of("9.4.0", "8.0.46", "8.40.1")) {
            assertThrows(AssertionError.class, () -> WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase("nexus_test", "nexus_test_user@localhost", version));
        }
        assertThrows(AssertionError.class, () -> WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase("nexus", "nexus_test_user@localhost", "8.4.13"));
        assertThrows(AssertionError.class, () -> WebhookJvmCrashIntegrationTests.requireAcceptanceDatabase("nexus_test", "root@localhost", "8.4.13"));
    }

    @Test
    void failureTailRedactsKnownSecretsAndCredentialAssignmentsButKeepsCause() {
        String log = "startup password=unknown-password\ncrypto value known-master-key\n"
                + "JWT eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.abc123\n"
                + "authorization=ApiKey unknown-api-key\nauthorization: Bearer unknown-bearer\n"
                + "signature=unknown-signature claim_token=old-token\n"
                + "{\"password\":\"quoted secret value\", \"authorization\":\"ApiKey json-api-key\"}\n"
                + "Caused by: java.lang.IllegalStateException: child failed to start";
        String tail = WebhookJvmCrashIntegrationTests.sanitizedTail(log, List.of("known-master-key"));
        for (String secret : List.of("unknown-password", "known-master-key", "eyJhbGciOiJIUzI1NiJ9", "unknown-api-key", "unknown-bearer", "unknown-signature", "old-token", "quoted secret value", "json-api-key"))
            assertFalse(tail.contains(secret));
        assertTrue(tail.contains("Caused by: java.lang.IllegalStateException: child failed to start"));
    }

    @Test
    void failureTailIsBoundedAndIncludesLastStartupError() {
        String tail = WebhookJvmCrashIntegrationTests.sanitizedTail("old line\n".repeat(100) + "x".repeat(20000) + "\nlast startup error", List.of());
        assertTrue(tail.length() <= 16000);
        assertFalse(tail.contains("old line"));
        assertTrue(tail.endsWith("last startup error"));
    }
}
