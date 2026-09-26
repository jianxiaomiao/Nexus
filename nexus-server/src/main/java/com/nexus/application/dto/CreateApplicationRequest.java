package com.nexus.application.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateApplicationRequest(
        @NotBlank(message = "应用名称不能为空")
        @Size(max = 64, message = "应用名称不能超过64个字符")
        String name
) {}
