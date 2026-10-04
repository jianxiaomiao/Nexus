package com.nexus.application.controller;

import com.nexus.application.dto.*;
import com.nexus.application.service.ApplicationServiceImpl;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    @GetMapping
    public ApiResponse<List<ApplicationResponse>> listMine(
            @RequestHeader(value = "Authorization", required = false) String authorization) {
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<>("SUCCESS", "查询成功",
                applicationService.listMyApplications(userId));
    }

    @PutMapping
    public ApiResponse<ApplicationResponse> updateMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @Valid @RequestBody UpdateApplicationRequest updateApplicationRequeste
            ){
        long userId = bearerUserIdResolver.resolve(authorization);
        return new ApiResponse<ApplicationResponse>(
                "SUCCESS",
                "更新成功",
                applicationService.updateMyApplication(userId,updateApplicationRequeste)
        );
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> deleteMine(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable Long id
            ){
        long userId = bearerUserIdResolver.resolve(authorization);
        applicationService.deleteMyApplication(userId, id);
        return new ApiResponse<Void>(
                "SUCCESS",
                "删除成功",
                null
        );
    }

}
