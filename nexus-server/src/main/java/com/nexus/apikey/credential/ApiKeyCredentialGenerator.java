package com.nexus.apikey.credential;

import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Component
public class ApiKeyCredentialGenerator {
    private static final String KEY_PREFIX = "nxk_v1_";
    private static final int SECRET_BYTES = 32;
    private static final int SECRET_TEXT_LENGTH = 43;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    public GeneratedApiKey generate() {
        String publicId = UUID.randomUUID().toString();
        byte[] secretBytes = new byte[SECRET_BYTES];
        SECURE_RANDOM.nextBytes(secretBytes);
        String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);

        String plaintextKey = KEY_PREFIX + publicId + "_" + secret;
        String keyPreview = "nxk_" + secret.substring(0, 6)
                + "*****" + secret.substring(secret.length() - 4);

        return new GeneratedApiKey(publicId, hashSecret(secret), keyPreview, plaintextKey);
    }

    public boolean matchesSecret(String suppliedSecret, String storedHash) {
        if (suppliedSecret == null || suppliedSecret.length() != SECRET_TEXT_LENGTH
                || storedHash == null || storedHash.length() != 64) {
            return false;
        }

        byte[] expectedHash;
        try {
            expectedHash = HexFormat.of().parseHex(storedHash);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        return MessageDigest.isEqual(sha256(suppliedSecret), expectedHash);
    }

    private String hashSecret(String secret) {
        return HexFormat.of().formatHex(sha256(secret));
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
