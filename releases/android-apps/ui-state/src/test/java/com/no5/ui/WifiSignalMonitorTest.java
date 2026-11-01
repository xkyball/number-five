package com.no5.ui;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public final class WifiSignalMonitorTest {
    @Test public void visibleConsumersShareOneBackendAndOnePoll() {
        Fixture f = new Fixture(); List<WifiSignal> first = new ArrayList<WifiSignal>();
        List<WifiSignal> second = new ArrayList<WifiSignal>();
        WifiSignalMonitor.Handle a = f.monitor.subscribe(first::add);
        WifiSignalMonitor.Handle b = f.monitor.subscribe(second::add);
        assertEquals(1, f.starts); assertEquals(0, f.reads);
        f.advanceTo(0); assertEquals(1, f.reads);
        assertEquals(WifiSignal.connected(-70), first.get(1));
        assertEquals(first, second); a.close(); assertEquals(0, f.stops);
        b.close(); b.close(); assertEquals(1, f.stops); assertNull(f.pending);
    }
    @Test public void passiveEventBurstsAndResumeRefreshCannotPollFasterThanOneHz() {
        Fixture f = new Fixture(); WifiSignalMonitor.Handle h = f.monitor.subscribe(value -> { });
        f.advanceTo(0);
        for (int t = 10; t < 1000; t += 10) { f.advanceTo(t); h.refresh(); f.monitor.refresh(); }
        assertEquals(1, f.reads); assertEquals(1000, f.at);
        f.advanceTo(1000); assertEquals(2, f.reads);
        h.close(); f.advanceTo(1010);
        WifiSignalMonitor.Handle resumed = f.monitor.subscribe(value -> { }); resumed.refresh();
        f.advanceTo(1999); assertEquals(2, f.reads);
        f.advanceTo(2000); assertEquals(3, f.reads); resumed.close();
    }
    @Test public void hiddenConsumersDoNoWorkAndResumeCannotPublishOldCachedRssi() {
        Fixture f = new Fixture(); WifiSignalMonitor.Handle h = f.monitor.subscribe(value -> { });
        f.advanceTo(0); h.close(); f.next = WifiSignal.off(); f.advanceTo(10000);
        assertEquals(1, f.reads); assertEquals(1, f.stops);
        List<WifiSignal> events = new ArrayList<WifiSignal>();
        WifiSignalMonitor.Handle resumed = f.monitor.subscribe(events::add);
        assertEquals(WifiSignal.unknown(), events.get(0));
        f.advanceTo(10000); assertEquals(WifiSignal.off(), events.get(1)); resumed.close();
    }
    @Test public void actualRssiChangesPublishEvenWhenBarCountIsUnchanged() {
        Fixture f = new Fixture(); List<WifiSignal> events = new ArrayList<WifiSignal>();
        WifiSignalMonitor.Handle h = f.monitor.subscribe(events::add);
        f.advanceTo(0); f.next = WifiSignal.connected(-71); f.advanceTo(1000);
        assertEquals(3, events.size()); assertEquals(Integer.valueOf(-71), events.get(2).rssiDbm);
        f.advanceTo(2000); assertEquals(3, events.size()); h.close();
    }
    @Test public void platformFailureWithdrawsPreviousSignalAndKeepsTheBoundedRecoveryPoll() {
        Fixture f = new Fixture(); List<WifiSignal> events = new ArrayList<WifiSignal>();
        WifiSignalMonitor.Handle h = f.monitor.subscribe(events::add); f.advanceTo(0);
        f.fail = true; f.advanceTo(1000); assertEquals(WifiSignal.unknown(), events.get(2));
        f.fail = false; f.next = WifiSignal.disconnected(); f.advanceTo(2000);
        assertEquals(WifiSignal.disconnected(), events.get(3)); h.close();
    }
    @Test public void lateCancelledWakeCannotReadAfterLastConsumerCloses() {
        Fixture f = new Fixture(); WifiSignalMonitor.Handle h = f.monitor.subscribe(value -> { });
        Runnable queued = f.pending; h.close(); queued.run(); h.refresh();
        assertEquals(0, f.reads); assertNull(f.pending);
    }
    @Test public void consumerCountIsBoundedAndClosedSlotsCanBeReused() {
        Fixture f = new Fixture(); List<WifiSignalMonitor.Handle> handles = new ArrayList<WifiSignalMonitor.Handle>();
        for (int n = 0; n < 16; n++) handles.add(f.monitor.subscribe(value -> { }));
        try { f.monitor.subscribe(value -> { }); fail("expected visible-consumer bound"); }
        catch (IllegalStateException expected) { }
        handles.remove(0).close(); handles.add(f.monitor.subscribe(value -> { }));
        for (WifiSignalMonitor.Handle h : handles) h.close();
        assertEquals(1, f.starts); assertEquals(1, f.stops);
    }
    private static final class Fixture implements WifiSignalMonitor.Clock, WifiSignalMonitor.Scheduler, WifiSignalMonitor.Backend {
        long now, at; int starts, stops, reads; boolean fail;
        Runnable pending; WifiSignal next = WifiSignal.connected(-70);
        final WifiSignalMonitor monitor = new WifiSignalMonitor(this, this, this);
        @Override public long elapsedMs() { return now; }
        @Override public void schedule(Runnable task, long delayMs) { pending = task; at = now + delayMs; }
        @Override public void cancel(Runnable task) { if (pending == task) pending = null; }
        @Override public void start() { starts++; }
        @Override public void stop() { stops++; }
        @Override public WifiSignal read() { reads++; if (fail) throw new SecurityException("permission"); return next; }
        void advanceTo(long target) {
            while (pending != null && at <= target) { now = at; Runnable task = pending; pending = null; task.run(); }
            now = target;
        }
    }
}
