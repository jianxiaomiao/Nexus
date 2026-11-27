package com.nexus.admin.dto;

import jakarta.validation.constraints.NotNull;

public record UpdateStatusRequest(
        @NotNull(message = "状态不能为空")
        Integer status
) {
}
