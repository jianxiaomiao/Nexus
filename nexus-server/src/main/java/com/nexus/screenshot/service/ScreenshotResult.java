package com.nexus.screenshot.service;

public record ScreenshotResult(byte[] png, int blockedResources, boolean resourceLimitReached) {
    public ScreenshotResult(byte[] png, int blockedResources) { this(png, blockedResources, false); }
}
