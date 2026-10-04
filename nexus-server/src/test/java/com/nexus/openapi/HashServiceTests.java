package com.nexus.openapi;

import com.nexus.openapi.dto.HashAlgorithm;
import com.nexus.openapi.exception.HashAlgorithmRequiredException;
import com.nexus.openapi.exception.HashContentRequiredException;
import com.nexus.openapi.exception.HashInputTooLargeException;
import com.nexus.openapi.service.HashService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HashServiceTests {
    private final HashService service = new HashService();

    @Test
    void missingContentHasSpecificFailure() {
        assertThrows(HashContentRequiredException.class,
                () -> service.computeHash(null, HashAlgorithm.SHA256));
    }

    @Test
    void missingAlgorithmHasSpecificFailure() {
        assertThrows(HashAlgorithmRequiredException.class,
                () -> service.computeHash("hello", null));
    }

    @Test
    void limitUsesUtf8BytesRatherThanCharacterCount() {
        assertEquals(64, service.computeHash("中".repeat(1365), HashAlgorithm.SHA256)
                .hashContent().length());
        assertThrows(HashInputTooLargeException.class,
                () -> service.computeHash("中".repeat(1366), HashAlgorithm.SHA256));
    }
}
