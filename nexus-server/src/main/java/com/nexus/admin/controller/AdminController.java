package com.nexus.admin.controller;

import com.nexus.admin.dto.AdminApplicationListItemResponse;
import com.nexus.admin.dto.AdminUserListItemResponse;
import com.nexus.admin.dto.UpdateStatusRequest;
import com.nexus.admin.service.AdminService;
import com.nexus.auth.web.BearerUserIdResolver;
import com.nexus.common.web.ApiResponse;
import com.nexus.common.web.ListPage;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private final BearerUserIdResolver bearerUserIdResolver;
    private final AdminService adminService;

    @GetMapping("/users")
    public ApiResponse<ListPage<AdminUserListItemResponse>> listUsers(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) String email) {
        bearerUserIdResolver.resolveAdmin(authorization);
        return new ApiResponse<>("SUCCESS", "查询成功", adminService.listUsers(current, size, email));
    }

    @PatchMapping("/users/{id}/status")
    public ApiResponse<Void> updateUserStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id,
            @Valid @RequestBody UpdateStatusRequest request) {
        long adminUserId = bearerUserIdResolver.resolveAdmin(authorization);
        adminService.updateUserStatus(adminUserId, id, request.status());
        return new ApiResponse<>("SUCCESS", "用户状态已更新", null);
    }

    @GetMapping("/applications")
    public ApiResponse<ListPage<AdminApplicationListItemResponse>> listApplications(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @RequestParam(defaultValue = "1") long current,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) Long ownerUserId) {
        bearerUserIdResolver.resolveAdmin(authorization);
        return new ApiResponse<>("SUCCESS", "查询成功", adminService.listApplications(current, size, ownerUserId));
    }

    @PatchMapping("/applications/{id}/status")
    public ApiResponse<Void> updateApplicationStatus(
            @RequestHeader(value = "Authorization", required = false) String authorization,
            @PathVariable long id,
            @Valid @RequestBody UpdateStatusRequest request) {
        bearerUserIdResolver.resolveAdmin(authorization);
        adminService.updateApplicationStatus(id, request.status());
        return new ApiResponse<>("SUCCESS", "应用状态已更新", null);
    }
}
