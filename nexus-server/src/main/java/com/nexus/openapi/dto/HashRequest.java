package com.nexus.openapi.dto;

import jakarta.validation.constraints.NotNull;

public record HashRequest (
        @NotNull(message = "算法为空")
        HashAlgorithm algorithm,
        // 允许空字符串；统一由 Service 按 UTF-8 字节数检查长度。
        @NotNull(message = "输入内容为空")
        String value
){
}
