package com.no5.ui;

import java.util.LinkedHashSet;
import java.util.Set;

/** Main-thread, Android-independent visibility/refcount and one-Hz scheduling policy. */
final class WifiSignalMonitor {
    interface Clock { long elapsedMs(); }
    interface Scheduler { void schedule(Runnable task, long delayMs); void cancel(Runnable task); }
    interface Backend { void start(); void stop(); WifiSignal read(); }
    interface Listener { void changed(WifiSignal value); }
    static final long INTERVAL_MS = 1000;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Backend backend;
    private final Set<Handle> handles = new LinkedHashSet<Handle>();
    private WifiSignal value = WifiSignal.unknown();
    private long lastRead = -1;
    private final Runnable poll = new Runnable() { @Override public void run() {
        if (handles.isEmpty()) return;
        long now = clock.elapsedMs();
        if (lastRead >= 0 && now - lastRead < INTERVAL_MS) { refresh(); return; }
        lastRead = now;
        WifiSignal next;
        try { next = backend.read(); } catch (RuntimeException failure) { next = WifiSignal.unknown(); }
        if (next == null) next = WifiSignal.unknown();
        if (!next.equals(value)) {
            value = next;
            for (Handle handle : handles.toArray(new Handle[0]))
                if (!handle.closed) handle.listener.changed(value);
        }
        if (!handles.isEmpty()) refresh();
    } };

    WifiSignalMonitor(Clock clock, Scheduler scheduler, Backend backend) {
        this.clock = clock; this.scheduler = scheduler; this.backend = backend;
    }
    Handle subscribe(Listener listener) {
        if (listener == null) throw new NullPointerException("listener");
        if (handles.size() >= 16) throw new IllegalStateException("Too many visible WLAN consumers");
        Handle handle = new Handle(listener);
        handles.add(handle);
        if (handles.size() == 1) {
            value = WifiSignal.unknown();
            backend.start(); refresh();
        }
        listener.changed(value);
        return handle;
    }
    void refresh() {
        scheduler.cancel(poll);
        if (handles.isEmpty()) return;
        long delay = lastRead < 0 ? 0 : Math.max(0, INTERVAL_MS - (clock.elapsedMs() - lastRead));
        scheduler.schedule(poll, delay);
    }
    final class Handle implements AutoCloseable {
        private final Listener listener;
        private boolean closed;
        private Handle(Listener listener) { this.listener = listener; }
        void refresh() { if (!closed) WifiSignalMonitor.this.refresh(); }
        @Override public void close() {
            if (closed) return;
            closed = true; handles.remove(this);
            if (handles.isEmpty()) {
                scheduler.cancel(poll); backend.stop(); value = WifiSignal.unknown();
            }
        }
    }
}
