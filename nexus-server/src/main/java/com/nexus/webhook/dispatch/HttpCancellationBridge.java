package com.nexus.webhook.dispatch;

import java.util.Objects;
import java.util.concurrent.Future;

/** Registers before the HTTP operation starts; any later binding is cancelled after lease loss. */
public final class HttpCancellationBridge {
    private boolean cancelled;
    private Future<?> current;

    public HttpCancellationBridge(CancellationToken token) {
        Objects.requireNonNull(token).onCancel(this::cancel);
    }

    public void bind(Future<?> future) {
        Objects.requireNonNull(future);
        synchronized (this) {
            if (!cancelled) {
                if (current != null) throw new IllegalStateException("HTTP operation already bound");
                current = future;
                return;
            }
        }
        future.cancel(true);
    }

    private void cancel() {
        Future<?> pending;
        synchronized (this) { cancelled = true; pending = current; current = null; }
        if (pending != null) pending.cancel(true);
    }
}
