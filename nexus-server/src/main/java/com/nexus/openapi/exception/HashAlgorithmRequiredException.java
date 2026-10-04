package com.nexus.openapi.exception;

public class HashAlgorithmRequiredException extends RuntimeException {
    public HashAlgorithmRequiredException() {
        super("摘要算法不能为空");
    }
}
