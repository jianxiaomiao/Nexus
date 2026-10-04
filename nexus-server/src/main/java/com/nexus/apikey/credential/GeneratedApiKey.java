package com.nexus.apikey.credential;

/** The plaintext key must only be returned in the creation response. */
public record GeneratedApiKey(
        String publicId,
        String secretHash,
        String keyPreview,
        String plaintextKey
) {
    @Override
    public String toString() {
        return "GeneratedApiKey[publicId=" + publicId + ", keyPreview=" + keyPreview + "]";
    }
}
