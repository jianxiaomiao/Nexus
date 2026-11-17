package com.nexus.testing;

import com.nexus.auth.entity.AuthSession;
import com.nexus.auth.mapper.AuthSessionMapper;
import com.nexus.auth.token.*;
import java.time.*;
import java.time.temporal.ChronoUnit;

/** 管理接口测试的真实会话夹具；不用于生产，也不绕过Resolver的会话检查。 */
public final class TestSessionTokens {
    private TestSessionTokens() { }
    public static IssuedAccessToken issue(JwtTokenService tokens, AuthSessionMapper sessions, long userId) {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        AuthSession session = new AuthSession();
        session.setUserId(userId);
        session.setCreatedAt(LocalDateTime.ofInstant(now, ZoneOffset.UTC));
        session.setExpiresAt(LocalDateTime.ofInstant(now.plus(Duration.ofHours(24)), ZoneOffset.UTC));
        sessions.insert(session);
        return tokens.issueAccessToken(userId, session.getId(), session.getExpiresAt().toInstant(ZoneOffset.UTC));
    }

    /** 仅清理本夹具创建的无RT会话；真实登录会话的完整清理由相应流程测试负责。 */
    public static void deleteForUser(AuthSessionMapper sessions, long userId) {
        sessions.delete(com.baomidou.mybatisplus.core.toolkit.Wrappers.<AuthSession>lambdaQuery().eq(AuthSession::getUserId, userId));
    }
}
