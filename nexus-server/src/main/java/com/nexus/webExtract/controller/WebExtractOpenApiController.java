package com.nexus.webExtract.controller;

import com.nexus.common.web.ApiResponse;
import com.nexus.openapi.web.MachineIdentityResolver;
import com.nexus.usage.ApiCode;
import com.nexus.usage.web.UsageApi;
import com.nexus.webExtract.dto.WebExtractRequest;
import com.nexus.webExtract.dto.WebExtractResponse;
import com.nexus.webExtract.service.WebExtractService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@AllArgsConstructor
@RequestMapping("/v1/web")
public class WebExtractOpenApiController {
    private final MachineIdentityResolver machineIdentityResolver;
    private final WebExtractService webExtractService;

    @UsageApi(ApiCode.WEB_EXTRACT)
    @PostMapping("/extract")
    public ApiResponse<WebExtractResponse> extract(HttpServletRequest request, @Valid @RequestBody WebExtractRequest webExtractRequest) {
        // 显式要求机器身份，避免绕过 Filter 配置时误执行接口。
        machineIdentityResolver.require(request);
        WebExtractResponse result = webExtractService.extract(webExtractRequest.url());

        return new ApiResponse<>(
                "SUCCESS",
                "内容提取成功",
                result
        );
    }
}
