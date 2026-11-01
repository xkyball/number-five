package com.no5.ui;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UiMediaExpiryTest {
    @Test public void silentProducerExpiresOnceWithoutNewRevisionOrFrames() {
        Fixture fixture = new Fixture();
        UiStateSnapshot snapshot = snapshot(42, 10000);
        fixture.expiry.observe(snapshot);
        assertEquals(15000, fixture.scheduler.at);
        fixture.scheduler.advanceTo(14999); assertEquals(0, fixture.notices);
        fixture.scheduler.advanceTo(15000); assertEquals(1, fixture.notices);
        fixture.scheduler.advanceTo(30000); assertEquals(1, fixture.notices);
        assertEquals(42, snapshot.revision);
        assertEquals(10000, snapshot.media.receivedAtElapsedMs);
        assertEquals(100000, snapshot.media.observedAtEpochMs);
    }
    @Test public void identicalObservationCannotExtendTheDeadline() {
        Fixture fixture = new Fixture();
        fixture.expiry.observe(snapshot(42, 10000));
        fixture.scheduler.advanceTo(14000);
        fixture.expiry.observe(snapshot(43, 10000));
        assertEquals(15000, fixture.scheduler.at);
        fixture.scheduler.advanceTo(15000); assertEquals(1, fixture.notices);
    }
    @Test public void genuinelyNewObservationReplacesTheOldLocalDeadline() {
        Fixture fixture = new Fixture();
        fixture.expiry.observe(snapshot(42, 10000));
        fixture.scheduler.advanceTo(14000);
        fixture.expiry.observe(snapshot(43, 14000));
        fixture.scheduler.advanceTo(15000); assertEquals(0, fixture.notices);
        fixture.scheduler.advanceTo(19000); assertEquals(1, fixture.notices);
    }
    @Test public void stoppedOrDisconnectedClientsHaveNoExpiryWakeups() {
        Fixture fixture = new Fixture();
        fixture.expiry.observe(snapshot(42, 10000)); fixture.expiry.stop();
        fixture.scheduler.advanceTo(15000); assertEquals(0, fixture.notices);
        fixture.expiry.observe(snapshot(42, 14000).withConnection(false));
        assertNull(fixture.scheduler.pending);
    }
    private static UiStateSnapshot snapshot(long revision, long observationElapsed) {
        return new UiStateSnapshot("de", revision, true,
                new UiStateSnapshot.NowPlaying("Short title", "Artist", "PLAYING", 1,
                        100000 + observationElapsed - 10000, observationElapsed, true));
    }
    private static final class Fixture {
        final FakeScheduler scheduler = new FakeScheduler();
        int notices;
        final UiMediaExpiry expiry = new UiMediaExpiry(scheduler, scheduler, new UiMediaExpiry.Listener() {
            @Override public void expired(UiStateSnapshot snapshot) { notices++; }
        });
    }
    private static final class FakeScheduler implements UiMediaExpiry.Scheduler, UiMediaExpiry.Clock {
        long now = 10000, at;
        Runnable pending;
        @Override public long elapsedMs() { return now; }
        @Override public void schedule(Runnable task, long delayMs) { pending = task; at = now + delayMs; }
        @Override public void cancel(Runnable task) { if (pending == task) pending = null; }
        void advanceTo(long target) {
            while (pending != null && at <= target) { now = at; Runnable next = pending; pending = null; next.run(); }
            now = target;
        }
    }
}
