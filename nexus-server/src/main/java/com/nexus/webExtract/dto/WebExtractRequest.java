package com.nexus.webExtract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record WebExtractRequest(
        @NotBlank(message = "url不能为空")
        @Size(max = 2048, message = "url不能超过2048个字符")
        String url
) {
}
