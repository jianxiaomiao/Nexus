package com.nexus.application.exception;

public class ApplicationLimitExceededException extends RuntimeException {
    public ApplicationLimitExceededException(int maxPerUser) {
        super("每个用户最多只能创建 " + maxPerUser + " 个应用");
    }
}
