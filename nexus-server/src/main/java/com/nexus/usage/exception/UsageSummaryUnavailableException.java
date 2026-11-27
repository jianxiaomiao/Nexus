package com.nexus.usage.exception;

public class UsageSummaryUnavailableException extends RuntimeException {
    public UsageSummaryUnavailableException() {
        super("用量汇总暂不可用，请稍后重试");
    }
}
