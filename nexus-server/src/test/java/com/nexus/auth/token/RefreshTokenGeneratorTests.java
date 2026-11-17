package com.nexus.auth.token;

import com.nexus.auth.exception.InvalidRefreshTokenException;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class RefreshTokenGeneratorTests {
    // 公开的固定测试样本：字节 0..31 的无填充 Base64URL，不是实际登录凭据。
    private static final String FIXTURE = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8";
    private final RefreshTokenGenerator generator = new RefreshTokenGenerator();

    @Test
    void generatedTokenShouldContain32RandomBytesAndItsLookupHash() {
        GeneratedRefreshToken token = generator.generate();

        assertEquals(43, token.value().length());
        assertTrue(token.value().matches("[A-Za-z0-9_-]{43}"));
        assertEquals(32, Base64.getUrlDecoder().decode(token.value()).length);
        assertTrue(generator.isValidFormat(token.value()));
        assertTrue(token.tokenHash().matches("[0-9a-f]{64}"));
        assertEquals(generator.hash(token.value()), token.tokenHash());
    }

    @Test
    void separateGenerationsShouldHaveIndependentValuesAndHashes() {
        GeneratedRefreshToken first = generator.generate();
        GeneratedRefreshToken second = generator.generate();

        assertNotEquals(first.value(), second.value());
        assertNotEquals(first.tokenHash(), second.tokenHash());
    }

    @Test
    void hashShouldMatchTheKnownSha256OfTheTokenText() {
        assertTrue(generator.isValidFormat(FIXTURE));
        // 摘要对象是 43 字符原文，而不是解码后的 32 字节。
        assertEquals("ea866a757e4c38babfa8127cbe9a409d3e1f93a00ff1488ff735fcf917afffd0",
                generator.hash(FIXTURE));
    }

    @Test
    void tokenTextShouldRemainCaseSensitive() {
        String uppercase = "A".repeat(43);
        String lowercase = "a".repeat(42) + "A";

        assertTrue(generator.isValidFormat(uppercase));
        assertTrue(generator.isValidFormat(lowercase));
        assertNotEquals(generator.hash(uppercase), generator.hash(lowercase));
    }

    @Test
    void malformedInputsShouldBeRejectedWithoutEchoingCredentials() {
        String[] malformed = {
                null, "", " ".repeat(43), FIXTURE.substring(1), FIXTURE + "A", FIXTURE + "=",
                " " + FIXTURE, FIXTURE + "\n", FIXTURE.substring(0, 42) + " ",
                "+" + FIXTURE.substring(1), "/" + FIXTURE.substring(1),
                "." + FIXTURE.substring(1), "中" + FIXTURE.substring(1)
        };

        for (String value : malformed) {
            assertFalse(generator.isValidFormat(value));
            InvalidRefreshTokenException exception = assertThrows(
                    InvalidRefreshTokenException.class, () -> generator.hash(value));
            assertEquals("刷新凭据无效或已失效", exception.getMessage());
            assertNull(exception.getCause());
        }
    }

    @Test
    void nonCanonicalTrailingBitsShouldBeRejectedEvenWhenDecodingSucceeds() {
        String canonical = "A".repeat(43);
        String alias = "A".repeat(42) + "B";

        assertArrayEquals(Base64.getUrlDecoder().decode(canonical), Base64.getUrlDecoder().decode(alias));
        assertTrue(generator.isValidFormat(canonical));
        assertFalse(generator.isValidFormat(alias));
        assertThrows(InvalidRefreshTokenException.class, () -> generator.hash(alias));
    }

    @Test
    void diagnosticTextShouldHideBothPlaintextAndHash() {
        GeneratedRefreshToken token = generator.generate();

        assertFalse(token.toString().contains(token.value()));
        assertFalse(token.toString().contains(token.tokenHash()));
    }
}
