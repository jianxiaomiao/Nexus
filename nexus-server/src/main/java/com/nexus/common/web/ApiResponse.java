package com.nexus.common.web;

public record ApiResponse<T>(
        String code,
        String message,
        T data
) {
}
