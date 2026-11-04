package com.nexus.webhook.crypto;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;

@Component
public final class WebhookSecretCrypto {
    public static final String ALGORITHM = "AES-256-GCM";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int SECRET_LENGTH = 32;
    private static final int NONCE_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    private final WebhookCryptoProperties properties;
    private final SecureRandom secureRandom;

    @Autowired
    public WebhookSecretCrypto(WebhookCryptoProperties properties) {
        this(properties, new SecureRandom());
    }

    WebhookSecretCrypto(WebhookCryptoProperties properties, SecureRandom secureRandom) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
    }

    /** Creates a cryptographically random 256-bit webhook secret. */
    public byte[] generateSecret() {
        byte[] secret = new byte[SECRET_LENGTH];
        secureRandom.nextBytes(secret);
        return secret;
    }

    /** Displays a generated secret in URL-safe form without padding. */
    public String encodeForDisplay(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length != SECRET_LENGTH) {
            throw new IllegalArgumentException("Webhook secret must be exactly 32 bytes");
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(secret);
    }

    public EncryptedSecret encrypt(byte[] secret) {
        Objects.requireNonNull(secret, "secret");
        if (secret.length != SECRET_LENGTH) {
            throw new IllegalArgumentException("Webhook secret must be exactly 32 bytes");
        }
        byte[] nonce = new byte[NONCE_LENGTH];
        secureRandom.nextBytes(nonce);
        String version = properties.currentKeyVersion();
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key(version), new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            return new EncryptedSecret(cipher.doFinal(secret), nonce, ALGORITHM, version);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to encrypt webhook secret", exception);
        }
    }

    public byte[] decrypt(EncryptedSecret encryptedSecret) {
        Objects.requireNonNull(encryptedSecret, "encryptedSecret");
        if (!ALGORITHM.equals(encryptedSecret.encryptionAlgorithm())) {
            throw new IllegalArgumentException("Unsupported webhook secret encryption algorithm");
        }
        byte[] nonce = encryptedSecret.nonce();
        if (nonce.length != NONCE_LENGTH) {
            throw new IllegalArgumentException("Invalid webhook secret nonce length");
        }
        byte[] ciphertext = encryptedSecret.ciphertext();
        if (ciphertext.length < 16) {
            throw new IllegalArgumentException("Invalid webhook secret ciphertext length");
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key(encryptedSecret.encryptionKeyVersion()),
                    new GCMParameterSpec(TAG_LENGTH_BITS, nonce));
            byte[] plaintext = cipher.doFinal(ciphertext);
            if (plaintext.length != SECRET_LENGTH) {
                throw new IllegalArgumentException("Decrypted webhook secret has invalid length");
            }
            return plaintext;
        } catch (AEADBadTagException exception) {
            throw new IllegalArgumentException("Webhook secret authentication failed");
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to decrypt webhook secret", exception);
        }
    }

    private SecretKey key(String version) {
        return new SecretKeySpec(properties.keyBytes(version), "AES");
    }
}
