package com.nexus.openapi.service;

import com.nexus.openapi.dto.HashAlgorithm;
import com.nexus.openapi.dto.HashResponse;
import com.nexus.openapi.exception.HashAlgorithmRequiredException;
import com.nexus.openapi.exception.HashContentRequiredException;
import com.nexus.openapi.exception.HashInputTooLargeException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

@Service
public class HashService {

    private static final int MAX_UTF8_BYTE_SIZE = 4096;

    public HashResponse computeHash(String content, HashAlgorithm algorithm) {
        if (content == null) {
            throw new HashContentRequiredException();
        }
        if (algorithm == null) {
            throw new HashAlgorithmRequiredException();
        }
        byte[] utf8Bytes = content.getBytes(StandardCharsets.UTF_8);
        // 校验UTF-8字节上限
        if (utf8Bytes.length > MAX_UTF8_BYTE_SIZE) {
            throw new HashInputTooLargeException(MAX_UTF8_BYTE_SIZE);
        }

        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm.getJavaAlgorithmName());
            byte[] hashBytes = digest.digest(utf8Bytes);
            // 转小写16进制
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return new HashResponse(sb.toString());
        } catch (NoSuchAlgorithmException e) {
            // 枚举只保留SHA256/SHA512，理论不会走到这里，兜底异常
            throw new IllegalStateException("已配置的摘要算法不可用", e);
        }
    }
}
