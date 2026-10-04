package com.nexus.openapi.exception;

public class HashContentRequiredException extends RuntimeException {
    public HashContentRequiredException() {
        super("输入内容不能为空");
    }
}
