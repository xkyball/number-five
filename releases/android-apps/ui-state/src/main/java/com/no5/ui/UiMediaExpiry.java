package com.no5.ui;

/** One local deadline, independent of producer notifications or animation frames. */
public final class UiMediaExpiry {
    public interface Scheduler {
        void schedule(Runnable task, long delayMs);
        void cancel(Runnable task);
    }
    public interface Clock { long elapsedMs(); }
    public interface Listener { void expired(UiStateSnapshot snapshot); }
    private final Scheduler scheduler;
    private final Clock clock;
    private final Listener listener;
    private UiStateSnapshot snapshot;
    private boolean wasFresh;
    private final Runnable deadline = new Runnable() { public void run() {
        if (snapshot == null || !wasFresh) return;
        long now = clock.elapsedMs();
        if (snapshot.connected && snapshot.media.isFresh(now)) {
            schedule(now); // An early scheduler wake must not expire a still-valid observation.
            return;
        }
        wasFresh = false;
        listener.expired(snapshot);
    } };
    public UiMediaExpiry(Scheduler scheduler, Clock clock, Listener listener) {
        this.scheduler = scheduler; this.clock = clock; this.listener = listener;
    }
    public void observe(UiStateSnapshot snapshot) {
        scheduler.cancel(deadline);
        this.snapshot = snapshot;
        long now = clock.elapsedMs();
        wasFresh = snapshot.connected && snapshot.media.isFresh(now);
        if (wasFresh) schedule(now);
    }
    public void stop() { scheduler.cancel(deadline); snapshot = null; wasFresh = false; }
    private void schedule(long now) {
        long delay = snapshot.media.receivedAtElapsedMs + UiStateContract.MEDIA_FRESH_MS - now;
        scheduler.schedule(deadline, Math.max(1, delay));
    }
}
