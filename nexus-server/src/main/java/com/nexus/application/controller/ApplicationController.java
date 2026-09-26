package com.nexus.application.controller;

import com.nexus.application.dto.CreateApplicationRequest;
import com.nexus.application.dto.CreateApplicationResponse;
import com.nexus.application.service.ApplicationServiceImpl;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/application")
public class ApplicationController {
    private final ApplicationServiceImpl applicationService;

    private final BearerUserIdResolver bearerUserIdResolver;

    @ResponseStatus(HttpStatus.OK)
    @PostMapping("/create")
    public ApiResponse<CreateApplicationResponse> create(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody CreateApplicationRequest request
    ){
        //验证jwt，获取userid
        Long userId = bearerUserIdResolver.resolve(authorization);
        //创建application
        CreateApplicationResponse createApplicationResponse = applicationService.createApplication(userId, request);

        return new ApiResponse<CreateApplicationResponse>(
                "SUCCESS",
                "应用创建成功",
                createApplicationResponse
        );
    }
}
