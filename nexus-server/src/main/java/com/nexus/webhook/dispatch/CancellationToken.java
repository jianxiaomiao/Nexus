package com.nexus.webhook.dispatch;

import java.util.ArrayList;
import java.util.List;

/** Cooperative cancellation: callbacks must be fast and must not depend on execution. */
public final class CancellationToken {
    private boolean cancelled;
    private final List<Runnable> callbacks = new ArrayList<>();

    public synchronized boolean isCancelled() { return cancelled; }

    public void onCancel(Runnable callback) {
        java.util.Objects.requireNonNull(callback);
        synchronized (this) {
            if (!cancelled) { callbacks.add(callback); return; }
        }
        run(callback);
    }

    void cancel() {
        List<Runnable> pending;
        synchronized (this) {
            if (cancelled) return;
            cancelled = true;
            pending = List.copyOf(callbacks);
            callbacks.clear();
        }
        pending.forEach(CancellationToken::run);
    }

    private static void run(Runnable callback) {
        try { callback.run(); }
        catch (VirtualMachineError | ThreadDeath fatal) { throw fatal; }
        catch (Throwable ignored) { /* best effort; continue notifying the other callbacks */ }
    }
}
