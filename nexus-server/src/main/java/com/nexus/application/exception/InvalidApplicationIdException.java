package com.nexus.application.exception;

public class InvalidApplicationIdException extends RuntimeException {
    public InvalidApplicationIdException() {
        super("应用 ID 必须为正数");
    }
}
