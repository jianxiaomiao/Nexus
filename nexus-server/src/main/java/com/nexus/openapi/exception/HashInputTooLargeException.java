package com.nexus.openapi.exception;

public class HashInputTooLargeException extends RuntimeException {
    public HashInputTooLargeException(int maxUtf8Bytes) {
        super("输入内容的 UTF-8 字节数不能超过 " + maxUtf8Bytes);
    }
}
