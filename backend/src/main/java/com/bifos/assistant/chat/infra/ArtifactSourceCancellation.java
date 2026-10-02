package com.bifos.assistant.chat.infra;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class ArtifactSourceCancellation {
    private final AtomicReference<Closeable> active = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public void register(Closeable closeable) {
        if (closed.get()) {
            close(closeable);
            return;
        }
        active.set(closeable);
        if (closed.get()) {
            close(active.getAndSet(null));
        }
    }

    void close() {
        closed.set(true);
        close(active.getAndSet(null));
    }

    private static void close(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
            }
        }
    }
}
