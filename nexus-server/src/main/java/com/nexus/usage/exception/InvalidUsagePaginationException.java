package com.nexus.usage.exception;

public class InvalidUsagePaginationException extends RuntimeException {
    public InvalidUsagePaginationException() {
        super("页码必须大于等于1，每页条数必须在1到100之间");
    }
}
