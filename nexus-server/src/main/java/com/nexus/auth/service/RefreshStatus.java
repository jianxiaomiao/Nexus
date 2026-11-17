package com.nexus.auth.service;

/** 刷新事务的业务结果，不用于包装数据库或令牌签发异常。 */
public enum RefreshStatus {
    SUCCESS,
    INVALID,
    ACCOUNT_FORBIDDEN,

    /** 已确认旧 RT 重放；所属会话的撤销必须随本次事务成功提交。 */
    REPLAY_REVOKED
}
