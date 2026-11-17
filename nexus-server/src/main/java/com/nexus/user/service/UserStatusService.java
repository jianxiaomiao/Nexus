package com.nexus.user.service;

import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.auth.service.AuthSessionTransactions;
import com.nexus.user.entity.User;
import com.nexus.user.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** 可信后台调用入口；不增加面向普通用户的禁用/恢复HTTP接口。 */
@Service
@RequiredArgsConstructor
public class UserStatusService {
    private final UserMapper users;
    private final AuthSessionMapper sessions;
    private final AuthSessionTransactions transactions;
    private final Clock clock;

    public void disable(long userId) { change(userId, 1); }
    public void restore(long userId) { change(userId, 0); }

    private void change(long userId, int status) {
        transactions.execute(() -> {
            User user = users.selectForUpdate(userId);
            if (user == null || Integer.valueOf(1).equals(user.getIsDeleted())) {
                throw new IllegalArgumentException("用户不存在或已删除");
            }
            LocalDateTime now = LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MILLIS), ZoneOffset.UTC);
            users.updateStatus(userId, status, now);
            if (status == 1) sessions.revokeAll(userId, now);
            return null;
        });
    }
}
