package com.nexus.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexus.admin.dto.AdminApplicationListItemResponse;
import com.nexus.admin.dto.AdminUserListItemResponse;
import com.nexus.admin.exception.AdminResourceNotFoundException;
import com.nexus.admin.exception.InvalidAdminOperationException;
import com.nexus.application.entity.Application;
import com.nexus.application.mapper.ApplicationMapper;
import com.nexus.auth.entity.AuthSession;
import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.common.web.ListPage;
import com.nexus.common.web.ListPageParameters;
import com.nexus.quota.dto.QueryQuotaResponse;
import com.nexus.quota.service.ApplicationQuotaService;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final UserMapper userMapper;
    private final ApplicationMapper applicationMapper;
    private final AuthSessionMapper authSessionMapper;
    private final ApplicationQuotaService quotaService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ListPage<AdminUserListItemResponse> listUsers(long current, long size, String emailKeyword) {
        ListPageParameters.validate(current, size);
        String keyword = emailKeyword == null ? null : emailKeyword.strip();
        boolean hasKeyword = keyword != null && !keyword.isEmpty();

        Page<User> page = userMapper.selectPage(new Page<>(current, size),
                Wrappers.<User>lambdaQuery()
                        .eq(User::getIsDeleted, 0)
                        .like(hasKeyword, User::getEmail, keyword)
                        .orderByDesc(User::getCreatedAt)
                        .orderByDesc(User::getId));

        List<User> users = page.getRecords();
        Map<Long, Long> appCounts = countActiveApplicationsByUsers(users);
        List<AdminUserListItemResponse> responses = new ArrayList<>(users.size());
        for (User user : users) {
            responses.add(new AdminUserListItemResponse(
                    user.getId(),
                    user.getEmail(),
                    user.getDisplayName(),
                    user.getRole() != null ? user.getRole() : "USER",
                    user.getStatus() != null ? user.getStatus() : 0,
                    appCounts.getOrDefault(user.getId(), 0L),
                    user.getCreatedAt().toInstant(ZoneOffset.UTC)
            ));
        }
        return new ListPage<>(page.getTotal(), page.getCurrent(), page.getSize(), responses);
    }

    @Transactional
    public void updateUserStatus(long adminUserId, long targetUserId, int status) {
        if (targetUserId <= 0) {
            throw new InvalidAdminOperationException("用户 ID 必须为正数");
        }
        if (status != 0 && status != 1) {
            throw new InvalidAdminOperationException("用户状态只能是 0 或 1");
        }
        if (adminUserId == targetUserId) {
            throw new InvalidAdminOperationException("不能修改当前管理员自己的状态");
        }

        User target = userMapper.selectOne(Wrappers.<User>lambdaQuery()
                .eq(User::getId, targetUserId)
                .eq(User::getIsDeleted, 0)
                .last("FOR UPDATE"));
        if (target == null) {
            throw new AdminResourceNotFoundException("用户不存在");
        }

        LocalDateTime now = utcNow();
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, targetUserId)
                .eq(User::getIsDeleted, 0)
                .set(User::getStatus, status)
                .set(User::getUpdatedAt, now));

        if (status == 1) {
            authSessionMapper.update(null, Wrappers.<AuthSession>lambdaUpdate()
                    .eq(AuthSession::getUserId, targetUserId)
                    .isNull(AuthSession::getRevokedAt)
                    .set(AuthSession::getRevokedAt, now));
        }
    }

    @Transactional(readOnly = true)
    public ListPage<AdminApplicationListItemResponse> listApplications(long current, long size, Long ownerUserId) {
        ListPageParameters.validate(current, size);
        if (ownerUserId != null && ownerUserId <= 0) {
            throw new InvalidAdminOperationException("用户 ID 必须为正数");
        }

        Page<Application> page = applicationMapper.selectPage(new Page<>(current, size),
                Wrappers.<Application>lambdaQuery()
                        .eq(Application::getIsDeleted, 0)
                        .eq(ownerUserId != null, Application::getOwnerUserId, ownerUserId)
                        .orderByDesc(Application::getCreatedAt)
                        .orderByDesc(Application::getId));

        List<Application> apps = page.getRecords();
        List<QueryQuotaResponse> quotas = quotaService.queryCurrentQuotas(apps);
        Map<Long, String> emailsByUserId = loadOwnerEmails(apps);

        List<AdminApplicationListItemResponse> responses = new ArrayList<>(apps.size());
        for (int i = 0; i < apps.size(); i++) {
            Application app = apps.get(i);
            responses.add(new AdminApplicationListItemResponse(
                    app.getId(),
                    app.getOwnerUserId(),
                    emailsByUserId.getOrDefault(app.getOwnerUserId(), ""),
                    app.getName(),
                    app.getStatus() != null ? app.getStatus() : 0,
                    quotas.get(i),
                    app.getCreatedAt().toInstant(ZoneOffset.UTC),
                    app.getUpdatedAt().toInstant(ZoneOffset.UTC)
            ));
        }
        return new ListPage<>(page.getTotal(), page.getCurrent(), page.getSize(), responses);
    }

    @Transactional
    public void updateApplicationStatus(long applicationId, int status) {
        if (applicationId <= 0) {
            throw new InvalidAdminOperationException("应用 ID 必须为正数");
        }
        if (status != 0 && status != 1) {
            throw new InvalidAdminOperationException("应用状态只能是 0 或 1");
        }

        LocalDateTime now = utcNow();
        int updated = applicationMapper.update(null, Wrappers.<Application>lambdaUpdate()
                .eq(Application::getId, applicationId)
                .eq(Application::getIsDeleted, 0)
                .set(Application::getStatus, status)
                .set(Application::getUpdatedAt, now));
        if (updated == 0) {
            throw new AdminResourceNotFoundException("应用不存在");
        }
    }

    private Map<Long, Long> countActiveApplicationsByUsers(List<User> users) {
        if (users.isEmpty()) {
            return Map.of();
        }
        Set<Long> userIds = users.stream().map(User::getId).collect(Collectors.toSet());
        List<Application> apps = applicationMapper.selectList(Wrappers.<Application>lambdaQuery()
                .select(Application::getOwnerUserId)
                .in(Application::getOwnerUserId, userIds)
                .eq(Application::getIsDeleted, 0));
        return apps.stream().collect(Collectors.groupingBy(Application::getOwnerUserId, Collectors.counting()));
    }

    private Map<Long, String> loadOwnerEmails(List<Application> apps) {
        if (apps.isEmpty()) {
            return Map.of();
        }
        Set<Long> ownerIds = apps.stream().map(Application::getOwnerUserId).collect(Collectors.toSet());
        return userMapper.selectList(Wrappers.<User>lambdaQuery()
                        .select(User::getId, User::getEmail)
                        .in(User::getId, ownerIds))
                .stream()
                .collect(Collectors.toMap(User::getId, User::getEmail, (left, right) -> left));
    }

    private LocalDateTime utcNow() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
    }
}
