package com.nexus.auth.service;

import com.nexus.auth.token.IssuedAccessToken;

/**
 * 刷新事务的内部处理结果。外层须等待事务提交成功，再返回令牌或抛出业务异常。
 * 数据库操作、生成凭据或签发异常应继续传播以触发回滚，不转换为本对象的失败状态。
 */
public record RefreshOutcome(
        RefreshStatus status,
        IssuedAccessToken accessToken,
        String refreshToken
) {
    public RefreshOutcome {
        if (status == null) {
            throw new IllegalArgumentException("刷新结果必须包含状态");
        }

        if (status == RefreshStatus.SUCCESS) {
            if (accessToken == null
                    || accessToken.value() == null
                    || accessToken.value().isBlank()
                    || accessToken.expiresInSeconds() <= 0) {
                throw new IllegalArgumentException("刷新成功必须包含非空 AT 和正数有效秒数");
            }
            if (refreshToken == null || refreshToken.isBlank()) {
                throw new IllegalArgumentException("刷新成功必须包含非空 RT");
            }
        } else if (accessToken != null || refreshToken != null) {
            throw new IllegalArgumentException("刷新失败不能携带令牌");
        }
    }

    public static RefreshOutcome success(IssuedAccessToken accessToken, String refreshToken) {
        return new RefreshOutcome(RefreshStatus.SUCCESS, accessToken, refreshToken);
    }

    public static RefreshOutcome invalid() {
        return new RefreshOutcome(RefreshStatus.INVALID, null, null);
    }

    public static RefreshOutcome accountForbidden() {
        return new RefreshOutcome(RefreshStatus.ACCOUNT_FORBIDDEN, null, null);
    }

    public static RefreshOutcome replayRevoked() {
        return new RefreshOutcome(RefreshStatus.REPLAY_REVOKED, null, null);
    }

    /** 诊断文本只保留状态，避免 record 默认输出 AT、RT 原文。 */
    @Override
    public String toString() {
        return "RefreshOutcome[status=" + status + "]";
    }
}
