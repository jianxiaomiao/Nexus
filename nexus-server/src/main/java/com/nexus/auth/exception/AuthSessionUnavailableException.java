package com.nexus.auth.exception;

/**
 * 会话操作最终不可用。不得据此声称撤销成功或鼓励自动重试旧 RT。
 * 具体事务回滚、重试资格及次数由 Service 控制，不由全局异常处理器执行。
 */
public class AuthSessionUnavailableException extends RuntimeException {
    public AuthSessionUnavailableException(Throwable cause) {
        super("登录会话服务暂不可用", cause);
    }
}
