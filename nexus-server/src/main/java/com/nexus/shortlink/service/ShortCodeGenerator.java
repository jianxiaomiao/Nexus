package com.nexus.shortlink.service;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;

@Component
public class ShortCodeGenerator {
    private static final char[] ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int LENGTH = 12;
    private final SecureRandom random = new SecureRandom();

    public String generate() {
        char[] code = new char[LENGTH];
        for (int i = 0; i < code.length; i++) {
            code[i] = ALPHABET[random.nextInt(ALPHABET.length)];
        }
        return new String(code);
    }
}
