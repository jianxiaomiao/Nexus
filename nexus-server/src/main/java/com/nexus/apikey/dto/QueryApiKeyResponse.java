package com.nexus.apikey.dto;

import java.util.List;

public record QueryApiKeyResponse(
        List<ApiKeyResponse> apiKeyResponseList
) {
}
