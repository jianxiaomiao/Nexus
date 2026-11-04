package com.nexus.webhook.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Master keys are supplied as standard Base64 environment properties, for example
 * NEXUS_WEBHOOK_CRYPTO_KEYS_V1 and NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION.
 */
@ConfigurationProperties(prefix = "nexus.webhook.crypto")
public final class WebhookCryptoProperties {
    private final Map<String, String> keys;
    private final String currentKeyVersion;

    public WebhookCryptoProperties(Map<String, String> keys, String currentKeyVersion) {
        if (keys == null || keys.isEmpty()) {
            throw new IllegalArgumentException("At least one webhook crypto master key must be configured");
        }
        if (currentKeyVersion == null || currentKeyVersion.isBlank()) {
            throw new IllegalArgumentException("nexus.webhook.crypto.current-key-version must be configured");
        }

        Map<String, String> copiedKeys = new LinkedHashMap<>(keys);
        Map<String, byte[]> decoded = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : copiedKeys.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException("Webhook crypto key versions must not be blank");
            }
            if (entry.getValue() == null || entry.getValue().isBlank()) {
                throw new IllegalArgumentException("Webhook crypto master key must be configured for every version");
            }
            try {
                decoded.put(entry.getKey(), Base64.getDecoder().decode(entry.getValue()));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Webhook crypto master keys must use standard Base64", exception);
            }
        }
        decoded.forEach((version, key) -> {
            if (key.length != 32) {
                throw new IllegalArgumentException("Webhook crypto master key must decode to exactly 32 bytes for version " + version);
            }
        });
        if (!copiedKeys.containsKey(currentKeyVersion)) {
            throw new IllegalArgumentException("Current webhook crypto key version is not configured");
        }
        this.keys = Map.copyOf(copiedKeys);
        this.currentKeyVersion = currentKeyVersion;
    }

    public String currentKeyVersion() {
        return currentKeyVersion;
    }

    byte[] keyBytes(String version) {
        String encoded = keys.get(version);
        if (encoded == null) {
            throw new IllegalArgumentException("Webhook crypto key version is not configured");
        }
        return Base64.getDecoder().decode(encoded);
    }

    @Override
    public String toString() {
        return "WebhookCryptoProperties[keyVersions=" + keys.keySet()
                + ", currentKeyVersion=" + currentKeyVersion + "]";
    }
}
