package com.nexus.openapi.ratelimit;

import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.locks.ReentrantLock;

class ApplicationRateState {
    final ReentrantLock lock = new ReentrantLock();
    final ArrayDeque<Waiter> waiters = new ArrayDeque<>();
    long nextPermitNanos;
    long lastTouchedNanos;
    //表示该 State 已从 Map 删除，旧引用不能再使用。
    boolean retired;

    ScheduledFuture<?> drainTask;
}
