package com.no5.ui;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class No5UiClientDeadlineTest {
    @Test public void stalledIpcWorkerCannotKeepAQuietShortTitleVisuallyFresh() throws Exception {
        No5UiClient client = new No5UiClient(RuntimeEnvironment.getApplication());
        Handler worker = ReflectionHelpers.getField(client, "worker");
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        worker.post(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
        });
        try {
            assertTrue("IPC worker reached the deliberately stalled job", entered.await(2, TimeUnit.SECONDS));
            ArrayList<No5UiClient.Snapshot> notifications = new ArrayList<>();
            client.addObserver(notifications::add);
            client.start(); // Binding itself remains queued behind the stalled worker; no upstream request runs.
            shadowOf(Looper.getMainLooper()).idleFor(1, TimeUnit.MILLISECONDS);
            long observedElapsed = SystemClock.elapsedRealtime();
            assertTrue(observedElapsed > 0);
            No5UiClient.Snapshot original = new No5UiClient.Snapshot("de", 42, true,
                    new UiStateSnapshot.NowPlaying("Short title", "Artist", "PLAYING", 8,
                            100000, observedElapsed, true));
            Method publish = No5UiClient.class.getDeclaredMethod("publish", No5UiClient.Snapshot.class);
            publish.setAccessible(true); publish.invoke(client, original);
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(notifications.get(notifications.size()-1).media.isFresh(observedElapsed));
            client.stop(); shadowOf(Looper.getMainLooper()).idle();
            client.start(); shadowOf(Looper.getMainLooper()).idle();
            notifications.clear();
            shadowOf(Looper.getMainLooper()).idleFor(5, TimeUnit.SECONDS);
            assertEquals("one local deadline notification, with no worker progress", 1, notifications.size());
            No5UiClient.Snapshot expired = notifications.get(0);
            assertNotSame(original, expired);
            assertSame("main expiry does not overwrite canonical worker-owned state", original, client.getSnapshot());
            assertSame(original.media, expired.media);
            assertEquals(original.revision, expired.revision);
            assertEquals(original.media.observedAtEpochMs, expired.media.observedAtEpochMs);
            assertFalse(expired.media.isFresh(SystemClock.elapsedRealtime()));
            assertEquals(1, release.getCount());
        } finally { client.close(); release.countDown(); shadowOf(Looper.getMainLooper()).idle(); }
    }
}
