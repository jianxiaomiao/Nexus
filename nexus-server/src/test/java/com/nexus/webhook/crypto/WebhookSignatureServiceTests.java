package com.nexus.webhook.crypto;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSignatureServiceTests {
    private final WebhookSignatureService service = new WebhookSignatureService();

    @Test
    void hmacSha256PrimitiveShouldMatchRfc4231TestCase1() throws Exception {
        // RFC 4231's message lacks our required timestamp and dot prefix; the next test verifies that contract.
        byte[] key = new byte[20];
        Arrays.fill(key, (byte) 0x0b);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));

        assertEquals("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7",
                HexFormat.of().formatHex(mac.doFinal("Hi There".getBytes(StandardCharsets.US_ASCII))));
    }

    @Test
    void shouldMatchIndependentHmacSha256VectorForExactBinaryBody() {
        // Python hmac.new(bytes(range(32)), b'1700000000.' + bytes([0,255,46,10,128]), sha256).
        byte[] secret = new byte[32];
        for (int i = 0; i < secret.length; i++) {
            secret[i] = (byte) i;
        }
        byte[] body = {0, (byte) 255, '.', 10, (byte) 128};

        assertEquals("17c64ed3b65ca6cae77c9f1a9fae2669cc39e57e5f4c173cc35d5a4d60aecc72",
                service.sign(secret, 1700000000L, body));
    }

    @Test
    void shouldBeDeterministicAndChangeWhenAnyInputChanges() {
        byte[] secret = {1, 2, 3};
        byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
        String signature = service.sign(secret, 42, body);

        assertEquals(signature, service.sign(secret, 42, body));
        assertNotEquals(signature, service.sign(new byte[]{1, 2, 4}, 42, body));
        assertNotEquals(signature, service.sign(secret, 43, body));
        assertNotEquals(signature, service.sign(secret, 42, "Hello".getBytes(StandardCharsets.UTF_8)));
        assertTrue(signature.matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldAllowEmptyBodyAndTimestampZero() {
        assertTrue(service.sign(new byte[]{1}, 0, new byte[0]).matches("[0-9a-f]{64}"));
    }

    @Test
    void shouldRejectNullOrEmptySecretNullBodyAndNegativeTimestamp() {
        assertThrows(NullPointerException.class, () -> service.sign(null, 0, new byte[0]));
        assertThrows(IllegalArgumentException.class, () -> service.sign(new byte[0], 0, new byte[0]));
        assertThrows(NullPointerException.class, () -> service.sign(new byte[]{1}, 0, null));
        assertThrows(IllegalArgumentException.class, () -> service.sign(new byte[]{1}, -1, new byte[0]));
    }

    @Test
    void shouldNotModifyInputs() {
        byte[] secret = {1, 2, 3};
        byte[] body = {0, (byte) 255, 42};
        byte[] originalSecret = secret.clone();
        byte[] originalBody = body.clone();

        service.sign(secret, Long.MAX_VALUE, body);

        assertArrayEquals(originalSecret, secret);
        assertArrayEquals(originalBody, body);
    }

    @Test
    void shouldProduceCorrectResultsConcurrently() throws Exception {
        byte[] secret = {1, 2, 3};
        List<Callable<String>> calls = new ArrayList<>();
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            long timestamp = i;
            byte[] body = ("body-" + i).getBytes(StandardCharsets.UTF_8);
            expected.add(service.sign(secret, timestamp, body));
            calls.add(() -> service.sign(secret, timestamp, body));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            var results = executor.invokeAll(calls);
            for (int i = 0; i < results.size(); i++) {
                assertEquals(expected.get(i), results.get(i).get());
            }
        }
    }
}
