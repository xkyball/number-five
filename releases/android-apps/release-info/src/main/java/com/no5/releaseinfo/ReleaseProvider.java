package com.no5.releaseinfo;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.SystemClock;

/** Only ADB shell/root may read a snapshot or attest a verified installation. No general CRUD. */
public final class ReleaseProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public Bundle call(String method, String arg, Bundle extras) {
        int caller = Binder.getCallingUid();
        if (caller != 2000 && caller != 0) throw new SecurityException("Release evidence requires ADB shell");
        try {
            Bundle result = new Bundle();
            if ("snapshot".equals(method)) result.putString("record", ReleaseInfo.snapshot(getContext()).toString());
            else if ("record-installation".equals(method)) { ReleaseInfo.accept(getContext(), arg); result.putBoolean("accepted", true); }
            else if ("runtime-stats".equals(method)) {
                if (arg != null || extras != null && !extras.isEmpty()) throw new IllegalArgumentException();
                return runtimeStats(SystemClock.elapsedRealtime(), new RuntimeStatReader() {
                    @Override public String read(String name) { return Debug.getRuntimeStat(name); }
                });
            }
            else throw new IllegalArgumentException("Unsupported release operation");
            return result;
        } catch (Exception ignored) { throw new IllegalArgumentException("Release evidence rejected"); }
    }
    interface RuntimeStatReader { String read(String name); }

    /** Own-process cumulative ART counters (times in ms). Missing is explicit, never zero. */
    static Bundle runtimeStats(long capturedElapsedRealtimeMs, RuntimeStatReader reader) {
        if (capturedElapsedRealtimeMs < 0) throw new IllegalArgumentException();
        Bundle result = new Bundle();
        result.putLong("capturedElapsedRealtimeMs", capturedElapsedRealtimeMs);
        for (String name : new String[] { "art.gc.gc-count", "art.gc.gc-time",
                "art.gc.blocking-gc-count", "art.gc.blocking-gc-time" }) {
            String raw;
            try { raw = reader.read(name); }
            catch (RuntimeException | LinkageError unavailable) { raw = null; }
            Long value = counter(raw);
            result.putBoolean(name + ".available", value != null);
            if (value != null) result.putLong(name, value);
        }
        return result;
    }

    private static Long counter(String raw) {
        // ART's count/time values are unsigned decimal strings; cap work and Bundle values.
        if (raw == null || raw.isEmpty() || raw.length() > 19) return null;
        for (int index = 0; index < raw.length(); index++) {
            char digit = raw.charAt(index);
            if (digit < '0' || digit > '9') return null;
        }
        try { return Long.parseLong(raw); }
        catch (NumberFormatException unavailable) { return null; }
    }
    @Override public Cursor query(Uri u, String[] p, String s, String[] a, String o) { throw new UnsupportedOperationException(); }
    @Override public String getType(Uri u) { return null; }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException(); }
}
