package com.nexus.auth.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.function.Supplier;

/** 保证返回结果时已经提交，防止 REQUIRED 无意加入更外层事务。 */
@Component
public class AuthSessionTransactions {
    private final TransactionTemplate transactions;

    public AuthSessionTransactions(PlatformTransactionManager manager) {
        transactions = new TransactionTemplate(manager);
    }

    public <T> T execute(Supplier<T> action) {
        requireNoOuterTransaction();
        return transactions.execute(status -> action.get());
    }

    public static void requireNoOuterTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("登录会话操作必须在外层事务之外调用");
        }
    }
}
