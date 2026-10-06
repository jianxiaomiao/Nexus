package com.nexus.shortlink.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateShortLinkRequest(
        @NotNull @Min(1) Long id,
        @Size(max = 64) String name,
        @Min(0) @Max(1) Integer status
) {
}
