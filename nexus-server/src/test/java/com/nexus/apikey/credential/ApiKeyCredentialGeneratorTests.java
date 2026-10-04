package com.nexus.apikey.credential;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiKeyCredentialGeneratorTests {
    private final ApiKeyCredentialGenerator generator = new ApiKeyCredentialGenerator();

    @Test
    void generatedCredentialMatchesThePersistedVerifierAndPreviewContract() throws NoSuchAlgorithmException {
        GeneratedApiKey credential = generator.generate();
        String prefix = "nxk_v1_" + credential.publicId() + "_";
        assertEquals(4, UUID.fromString(credential.publicId()).version());
        assertTrue(credential.plaintextKey().startsWith(prefix));

        String secret = credential.plaintextKey().substring(prefix.length());
        assertEquals(43, secret.length());
        assertEquals(32, Base64.getUrlDecoder().decode(secret).length);
        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(secret.getBytes(StandardCharsets.UTF_8))
        );
        assertEquals(expectedHash, credential.secretHash());
        assertEquals("nxk_" + secret.substring(0, 6) + "*****" + secret.substring(39),
                credential.keyPreview());
        assertTrue(generator.matchesSecret(secret, credential.secretHash()));
        char differentLastCharacter = secret.charAt(42) == 'A' ? 'B' : 'A';
        assertFalse(generator.matchesSecret(
                secret.substring(0, 42) + differentLastCharacter, credential.secretHash()));
        assertFalse(credential.toString().contains(secret));
    }

    @Test
    void separateKeysGetIndependentPublicIdsAndSecrets() {
        GeneratedApiKey first = generator.generate();
        GeneratedApiKey second = generator.generate();

        assertNotEquals(first.publicId(), second.publicId());
        assertNotEquals(first.secretHash(), second.secretHash());
    }
}
