package com.nexus.webhook.crypto;

import java.util.Objects;

/** Encrypted webhook secret metadata. Byte arrays are copied at both boundaries. */
public record EncryptedSecret(
        byte[] ciphertext,
        byte[] nonce,
        String encryptionAlgorithm,
        String encryptionKeyVersion
) {
    public EncryptedSecret {
        ciphertext = Objects.requireNonNull(ciphertext, "ciphertext").clone();
        nonce = Objects.requireNonNull(nonce, "nonce").clone();
        Objects.requireNonNull(encryptionAlgorithm, "encryptionAlgorithm");
        Objects.requireNonNull(encryptionKeyVersion, "encryptionKeyVersion");
    }

    @Override
    public byte[] ciphertext() {
        return ciphertext.clone();
    }

    @Override
    public byte[] nonce() {
        return nonce.clone();
    }
}
