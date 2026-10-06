package com.nexus.shortlink.service;

import com.nexus.shortlink.exception.InvalidShortLinkRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShortLinkTargetPolicyTests {
    private final ShortLinkTargetPolicy policy = new ShortLinkTargetPolicy();

    @Test
    void acceptsExactHttpsHost() {
        assertDoesNotThrow(() -> policy.validate("https://www.douyin.com/video/1"));
    }

    @Test
    void rejectsLookalikeHostAndNonHttpsScheme() {
        assertThrows(InvalidShortLinkRequestException.class,
                () -> policy.validate("https://www.douyin.com.evil.example/video/1"));
        assertThrows(InvalidShortLinkRequestException.class,
                () -> policy.validate("http://www.douyin.com/video/1"));
    }
}
