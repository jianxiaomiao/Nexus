package com.nexus.openapi.controller;

import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.dto.HashRequest;
import com.nexus.openapi.dto.HashResponse;
import com.nexus.openapi.service.HashService;
import com.nexus.openapi.web.MachineIdentityResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@AllArgsConstructor
@RequestMapping("/v1/utils")
public class HashOpenApiController {
    private final MachineIdentityResolver machineIdentityResolver;
    private final HashService hashService;

    @PostMapping("/hash")
    public ApiResponse<HashResponse> generateHash(HttpServletRequest request, @Valid @RequestBody HashRequest hashRequest) {
        // 显式要求机器身份，避免绕过 Filter 配置时误执行接口。
        machineIdentityResolver.require(request);
        // 调用service计算hash
        HashResponse result = hashService.computeHash(hashRequest.value(), hashRequest.algorithm());

        return new ApiResponse<HashResponse>(
                "SUCCESS",
                "hash摘要成功",
                result
        );
    }
}
