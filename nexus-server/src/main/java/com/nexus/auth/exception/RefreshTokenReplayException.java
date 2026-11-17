package com.nexus.auth.exception;

/** 仅在确认重放且会话撤销事务成功提交后，由事务外抛出。 */
public class RefreshTokenReplayException extends RuntimeException {
    public RefreshTokenReplayException() {
        super("检测到刷新凭据重复使用，请重新登录");
    }
}
