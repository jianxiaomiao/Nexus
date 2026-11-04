package com.nexus.webhook.crypto;

import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import java.util.Objects;

@Service
public final class WebhookSignatureService {
    private static final String ALGORITHM = "HmacSHA256";

    /** Signs the decimal Unix timestamp, a dot, and the exact request body bytes. */
    public String sign(byte[] secret, long timestamp, byte[] rawRequestBody) {
        Objects.requireNonNull(secret, "secret");
        Objects.requireNonNull(rawRequestBody, "rawRequestBody");
        if (secret.length == 0) {
            throw new IllegalArgumentException("Webhook secret must not be empty");
        }
        if (timestamp < 0) {
            throw new IllegalArgumentException("Timestamp must not be negative");
        }

        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret, ALGORITHM));
            mac.update(Long.toString(timestamp).getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '.');
            return HexFormat.of().formatHex(mac.doFinal(rawRequestBody));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to sign webhook request", exception);
        }
    }
}
