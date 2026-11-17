package com.nexus.auth.token;

import com.nexus.auth.exception.InvalidRefreshTokenException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** 生成不透明 RT 及其查库摘要；有效期、消费和撤销状态由会话事务负责。 */
@Component
public class RefreshTokenGenerator {
    private static final int TOKEN_BYTES = 32;
    private static final int TOKEN_TEXT_LENGTH = 43;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    public GeneratedRefreshToken generate() {
        byte[] randomBytes = new byte[TOKEN_BYTES];
        SECURE_RANDOM.nextBytes(randomBytes);
        String value = ENCODER.encodeToString(randomBytes);
        return new GeneratedRefreshToken(value, hash(value));
    }

    /** 格式通过只表示可以计算摘要，不表示凭据存在或仍可消费。 */
    public boolean isValidFormat(String value) {
        if (value == null || value.length() != TOKEN_TEXT_LENGTH) {
            return false;
        }
        try {
            byte[] decoded = DECODER.decode(value);
            // 拒绝非规范的尾部填充位，不将不同的输入文本归一为同一凭据。
            return decoded.length == TOKEN_BYTES && ENCODER.encodeToString(decoded).equals(value);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /** 严格按 RT 原文的 UTF-8 字节计算 SHA-256，不 trim、不修改大小写。 */
    public String hash(String value) {
        if (!isValidFormat(value)) {
            throw new InvalidRefreshTokenException();
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
