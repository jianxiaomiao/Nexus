package com.nexus.openapi;

import com.nexus.openapi.dto.HashAlgorithm;
import com.nexus.openapi.exception.HashAlgorithmRequiredException;
import com.nexus.openapi.exception.HashContentRequiredException;
import com.nexus.openapi.exception.HashInputTooLargeException;
import com.nexus.openapi.service.HashService;
import com.nexus.auth.ApiKeyAuthenticator.ApiKeyIdentity;
import com.nexus.quota.service.ApplicationQuotaService;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HashServiceTests {
    private final ApplicationQuotaService quota = mock(ApplicationQuotaService.class);
    private final ApiKeyIdentity identity = new ApiKeyIdentity(1L, 2L);
    private final HashService service = new HashService(quota);

    @Test
    void missingContentHasSpecificFailure() {
        assertThrows(HashContentRequiredException.class,
                () -> service.computeHash(identity, null, HashAlgorithm.SHA256));
        verifyNoInteractions(quota);
    }

    @Test
    void missingAlgorithmHasSpecificFailure() {
        assertThrows(HashAlgorithmRequiredException.class,
                () -> service.computeHash(identity, "hello", null));
        verifyNoInteractions(quota);
    }

    @Test
    void limitUsesUtf8BytesRatherThanCharacterCount() {
        assertEquals(64, service.computeHash(identity, "中".repeat(1365), HashAlgorithm.SHA256)
                .hashContent().length());
        assertThrows(HashInputTooLargeException.class,
                () -> service.computeHash(identity, "中".repeat(1366), HashAlgorithm.SHA256));
        verify(quota, times(1)).consumeOnce(identity);
    }

    @Test void quotaRejectionPreventsDigestResponse() {
        doThrow(new com.nexus.quota.exception.ApplicationQuotaExhaustedException()).when(quota).consumeOnce(identity);
        assertThrows(com.nexus.quota.exception.ApplicationQuotaExhaustedException.class,
                () -> service.computeHash(identity, "hello", HashAlgorithm.SHA256));
    }
}
