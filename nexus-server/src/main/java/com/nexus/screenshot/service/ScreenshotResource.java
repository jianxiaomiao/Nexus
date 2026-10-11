package com.nexus.screenshot.service;

import java.util.Map;

public record ScreenshotResource(byte[] bytes, String contentType, Map<String, String> headers) {
    public ScreenshotResource(byte[] bytes, String contentType) { this(bytes, contentType, Map.of()); }
}
