package com.nexus.auth.dto;

public record CaptchaResponse(
        String captchaId,
        String imageDataUrl,
        int expiresInSeconds
) {
}
