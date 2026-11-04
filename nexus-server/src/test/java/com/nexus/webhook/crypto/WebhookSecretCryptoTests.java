package com.nexus.webhook.crypto;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.security.SecureRandom;
import java.lang.reflect.Modifier;

import static org.junit.jupiter.api.Assertions.*;

class WebhookSecretCryptoTests {
    private static final byte[] KEY_V1 = randomKey();
    private static final byte[] KEY_V2 = randomKey();
    private static final String KEY_V1_B64 = Base64.getEncoder().encodeToString(KEY_V1);
    private static final String KEY_V2_B64 = Base64.getEncoder().encodeToString(KEY_V2);

    @Test
    void shouldGenerate32ByteSecretAndDisplayBase64UrlWithoutPadding() {
        WebhookSecretCrypto crypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));

        byte[] first = crypto.generateSecret();
        byte[] second = crypto.generateSecret();

        assertEquals(32, first.length);
        assertEquals(32, second.length);
        assertFalse(java.util.Arrays.equals(first, second));
        String displayed = crypto.encodeForDisplay(first);
        assertFalse(displayed.contains("="));
        assertArrayEquals(first, Base64.getUrlDecoder().decode(displayed));
    }

    @Test
    void shouldEncryptAndDecryptAndUseConfiguredCurrentVersion() {
        WebhookSecretCrypto crypto = crypto(properties("v2", Map.of("v1", KEY_V1_B64, "v2", KEY_V2_B64)));
        byte[] secret = crypto.generateSecret();

        EncryptedSecret encrypted = crypto.encrypt(secret);

        assertEquals("v2", encrypted.encryptionKeyVersion());
        assertEquals(WebhookSecretCrypto.ALGORITHM, encrypted.encryptionAlgorithm());
        assertEquals(12, encrypted.nonce().length);
        assertEquals(secret.length + 16, encrypted.ciphertext().length);
        assertArrayEquals(secret, crypto.decrypt(encrypted));
    }

    @Test
    void shouldUseDifferentNonceForEachEncryption() {
        WebhookSecretCrypto crypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));
        byte[] secret = crypto.generateSecret();

        assertFalse(java.util.Arrays.equals(crypto.encrypt(secret).nonce(), crypto.encrypt(secret).nonce()));
    }

    @Test
    void shouldDecryptWithRetainedPreviousKeyVersion() {
        WebhookSecretCrypto oldCrypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));
        EncryptedSecret oldCiphertext = oldCrypto.encrypt(oldCrypto.generateSecret());
        WebhookSecretCrypto rotatedCrypto = crypto(
                properties("v2", Map.of("v1", KEY_V1_B64, "v2", KEY_V2_B64)));

        assertArrayEquals(oldCrypto.decrypt(oldCiphertext), rotatedCrypto.decrypt(oldCiphertext));
    }

    @Test
    void shouldRejectDecryptionWithWrongMasterKeyEvenWhenVersionMatches() {
        WebhookSecretCrypto encryptingCrypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));
        EncryptedSecret encrypted = encryptingCrypto.encrypt(encryptingCrypto.generateSecret());
        WebhookSecretCrypto wrongKeyCrypto = crypto(properties("v1", Map.of("v1", KEY_V2_B64)));

        assertThrows(IllegalArgumentException.class, () -> wrongKeyCrypto.decrypt(encrypted));
    }

    @Test
    void shouldRejectTamperedCiphertextOrNonce() {
        WebhookSecretCrypto crypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));
        EncryptedSecret encrypted = crypto.encrypt(crypto.generateSecret());
        byte[] changedCiphertext = encrypted.ciphertext();
        changedCiphertext[0] ^= 1;
        byte[] changedNonce = encrypted.nonce();
        changedNonce[0] ^= 1;

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                changedCiphertext, encrypted.nonce(), encrypted.encryptionAlgorithm(), encrypted.encryptionKeyVersion())));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                encrypted.ciphertext(), changedNonce, encrypted.encryptionAlgorithm(), encrypted.encryptionKeyVersion())));
    }

    @Test
    void shouldRejectUnsupportedAlgorithmUnknownVersionAndMalformedCiphertext() {
        WebhookSecretCrypto crypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));
        EncryptedSecret encrypted = crypto.encrypt(crypto.generateSecret());

        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                encrypted.ciphertext(), encrypted.nonce(), "AES/CBC", encrypted.encryptionKeyVersion())));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                encrypted.ciphertext(), encrypted.nonce(), encrypted.encryptionAlgorithm(), "v2")));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                new byte[15], encrypted.nonce(), encrypted.encryptionAlgorithm(), encrypted.encryptionKeyVersion())));
        assertThrows(IllegalArgumentException.class, () -> crypto.decrypt(new EncryptedSecret(
                encrypted.ciphertext(), new byte[11], encrypted.encryptionAlgorithm(), encrypted.encryptionKeyVersion())));
    }

    @Test
    void shouldRejectSecretWithWrongLength() {
        WebhookSecretCrypto crypto = crypto(properties("v1", Map.of("v1", KEY_V1_B64)));

        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> crypto.encodeForDisplay(new byte[33]));
    }

    @Test
    void encryptedSecretShouldDefensivelyCopyByteArrays() {
        byte[] ciphertext = {1, 2, 3};
        byte[] nonce = {4, 5, 6};
        EncryptedSecret encrypted = new EncryptedSecret(ciphertext, nonce, WebhookSecretCrypto.ALGORITHM, "v1");
        ciphertext[0] = 99;
        nonce[0] = 99;
        byte[] returnedCiphertext = encrypted.ciphertext();
        byte[] returnedNonce = encrypted.nonce();
        returnedCiphertext[1] = 99;
        returnedNonce[1] = 99;

        assertArrayEquals(new byte[]{1, 2, 3}, encrypted.ciphertext());
        assertArrayEquals(new byte[]{4, 5, 6}, encrypted.nonce());
    }

    @Test
    void propertiesShouldFailFastWhenKeysMissingShortMalformedOrCurrentVersionMissing() {
        assertThrows(IllegalArgumentException.class, () -> new WebhookCryptoProperties(Map.of(), "v1"));
        assertThrows(IllegalArgumentException.class, () -> new WebhookCryptoProperties(
                Map.of("v1", Base64.getEncoder().encodeToString(new byte[31])), "v1"));
        assertThrows(IllegalArgumentException.class, () -> new WebhookCryptoProperties(Map.of("v1", "not-base64!"), "v1"));
        assertThrows(IllegalArgumentException.class, () -> new WebhookCryptoProperties(Map.of("v1", KEY_V1_B64), "v2"));
    }

    @Test
    void propertiesToStringShouldNotExposeMasterKeyValues() {
        WebhookCryptoProperties properties = properties("v1", Map.of("v1", KEY_V1_B64));

        assertFalse(properties.toString().contains(KEY_V1_B64));
        assertFalse(properties.toString().contains(Base64.getEncoder().encodeToString(KEY_V1)));
        assertTrue(properties.toString().contains("v1"));
    }

    @Test
    void propertiesShouldNotExposeMasterKeysThroughPublicAccessors() throws Exception {
        var field = WebhookCryptoProperties.class.getDeclaredField("keys");
        assertTrue(Modifier.isPrivate(field.getModifiers()));
        assertTrue(Modifier.isFinal(field.getModifiers()));
        assertFalse(Modifier.isPublic(WebhookCryptoProperties.class
                .getDeclaredMethod("keyBytes", String.class).getModifiers()));
        assertThrows(NoSuchMethodException.class, () -> WebhookCryptoProperties.class.getMethod("keys"));
        assertThrows(NoSuchMethodException.class, () -> WebhookCryptoProperties.class.getMethod("getKeys"));
    }

    @Test
    void shouldBindVersionedKeysFromConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withPropertyValues(
                        "nexus.webhook.crypto.keys.v1=" + KEY_V1_B64,
                        "nexus.webhook.crypto.keys.v2=" + KEY_V2_B64,
                        "nexus.webhook.crypto.current-key-version=v2")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WebhookCryptoProperties properties = context.getBean(WebhookCryptoProperties.class);
                    assertEquals("v2", properties.currentKeyVersion());
                    assertArrayEquals(KEY_V1, properties.keyBytes("v1"));
                    assertArrayEquals(KEY_V2, properties.keyBytes("v2"));
                });
    }

    @Test
    void shouldBindVersionedKeysFromActualSystemEnvironmentPropertySource() {
        Map<String, Object> environment = new HashMap<>();
        environment.put("NEXUS_WEBHOOK_CRYPTO_KEYS_V1", KEY_V1_B64);
        environment.put("NEXUS_WEBHOOK_CRYPTO_KEYS_V2", KEY_V2_B64);
        environment.put("NEXUS_WEBHOOK_CRYPTO_CURRENT_KEY_VERSION", "v2");

        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new SystemEnvironmentPropertySource("systemEnvironment", environment)))
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WebhookCryptoProperties properties = context.getBean(WebhookCryptoProperties.class);
                    assertEquals("v2", properties.currentKeyVersion());
                    assertArrayEquals(KEY_V1, properties.keyBytes("v1"));
                    assertArrayEquals(KEY_V2, properties.keyBytes("v2"));
                });
    }

    @Test
    void configurationBindingShouldFailFastWithoutKeyOrCurrentVersion() {
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment", Map.of())))
                .run(context -> assertNotNull(context.getStartupFailure()));
        new ApplicationContextRunner()
                .withUserConfiguration(PropertiesConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment", Map.of())))
                .withPropertyValues("nexus.webhook.crypto.keys.v1=" + KEY_V1_B64)
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Test
    void springShouldCreateCryptoBeanWithValidConfiguration() {
        new ApplicationContextRunner()
                .withUserConfiguration(CryptoConfig.class)
                .withPropertyValues(
                        "nexus.webhook.crypto.keys.v1=" + KEY_V1_B64,
                        "nexus.webhook.crypto.current-key-version=v1")
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    WebhookSecretCrypto crypto = context.getBean(WebhookSecretCrypto.class);
                    byte[] secret = crypto.generateSecret();
                    assertArrayEquals(secret, crypto.decrypt(crypto.encrypt(secret)));
                });
    }

    @Test
    void springShouldFailToCreateCryptoBeanWhenKeyConfigurationIsMissing() {
        new ApplicationContextRunner()
                .withUserConfiguration(CryptoConfig.class)
                .withInitializer(context -> context.getEnvironment().getPropertySources()
                        .replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment", Map.of())))
                .run(context -> assertNotNull(context.getStartupFailure()));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(WebhookCryptoProperties.class)
    static class PropertiesConfig {
    }

    @Configuration(proxyBeanMethods = false)
    @Import({WebhookCryptoConfig.class, WebhookSecretCrypto.class})
    static class CryptoConfig {
    }

    private static WebhookCryptoProperties properties(String current, Map<String, String> keys) {
        return new WebhookCryptoProperties(keys, current);
    }

    private static WebhookSecretCrypto crypto(WebhookCryptoProperties properties) {
        return new WebhookSecretCrypto(properties);
    }

    private static byte[] randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return bytes;
    }
}
