package com.nexus.quota.exception;

public class ApplicationQuotaExhaustedException extends RuntimeException {
    public ApplicationQuotaExhaustedException() {
        super("应用当前720小时周期的调用额度已耗尽");
    }
}
