package com.no5.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import java.io.Closeable;
import java.util.concurrent.CopyOnWriteArrayList;

/** Explicit, identity-checked UI binding. All IPC is off main; observers are always called on main. */
public final class No5UiClient implements Closeable {
    public interface Observer {
        void onUiStateChanged(Snapshot snapshot);
        default void onUiError(String code) {}
    }
    public static final class Snapshot extends UiStateSnapshot {
        public Snapshot(String language, long revision, boolean connected, NowPlaying media) {
            super(language, revision, connected, media);
        }
        private static Snapshot copy(UiStateSnapshot state, boolean connected) {
            return new Snapshot(state.language, state.revision, connected, state.media);
        }
    }
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("no5-ui-client");
    private final Handler worker;
    private final CopyOnWriteArrayList<Observer> observers = new CopyOnWriteArrayList<>();
    private volatile Snapshot snapshot;
    private volatile boolean closed;
    private volatile boolean active;
    private boolean started, bound;
    private long bindingGeneration;
    private int controllerUid = -1;
    private IBinder remote;
    private ObserverBinder callback;
    private ServiceConnection connection;
    private long retryDelay = 250;
    private final UiMediaExpiry expiry;
    private final Object notificationLock = new Object();
    private boolean notificationQueued;
    private boolean expiryNotification;
    public No5UiClient(Context context) {
        this.context = context.getApplicationContext();
        snapshot = new Snapshot(UiLanguage.current(context), 0, false, UiStateSnapshot.NowPlaying.empty());
        thread.start();
        worker = new Handler(thread.getLooper());
        expiry = new UiMediaExpiry(new UiMediaExpiry.Scheduler() {
            @Override public void schedule(Runnable task, long delayMs) { main.postDelayed(task, delayMs); }
            @Override public void cancel(Runnable task) { main.removeCallbacks(task); }
        }, new UiMediaExpiry.Clock() {
            @Override public long elapsedMs() { return SystemClock.elapsedRealtime(); }
        }, new UiMediaExpiry.Listener() {
            @Override public void expired(UiStateSnapshot expected) {
                if (closed || !active) return;
                Snapshot latest = snapshot;
                boolean stillCurrent = latest.connected == expected.connected && latest.revision == expected.revision &&
                        latest.media.sourceGeneration == expected.media.sourceGeneration &&
                        latest.media.observedAtEpochMs == expected.media.observedAtEpochMs &&
                        latest.media.receivedAtElapsedMs == expected.media.receivedAtElapsedMs;
                // Do not write canonical state from main: an IPC-worker source replacement may
                // already have been adopted. Delivery below always re-reads that latest state.
                notifyLatest(stillCurrent);
            }
        });
    }
    public Snapshot getSnapshot() { return snapshot; }
    public void addObserver(Observer observer) {
        if (observer == null || closed) return;
        observers.addIfAbsent(observer);
        main.post(new Runnable() { public void run() { if (!closed && observers.contains(observer)) observer.onUiStateChanged(snapshot); } });
    }
    public void removeObserver(Observer observer) { observers.remove(observer); }
    public void start() {
        if (closed) return;
        active = true;
        notifyLatest(false); // Cached media must regain its deadline even if the IPC worker is stalled.
        if (!closed) worker.post(new Runnable() { public void run() { if (!started) { started = true; connect(); } } });
    }
    /** Suspend binding while retaining the last confirmed locale; start() can be called again. */
    public void stop() {
        active = false;
        main.post(new Runnable() { public void run() { if (!active || closed) expiry.stop(); } });
        if (!closed) worker.post(new Runnable() { public void run() { started = false; disconnect(); } });
    }
    public void setLanguage(String language) {
        UiLanguage.requireSupported(language);
        if (!closed) worker.post(new Runnable() { public void run() {
            if (remote == null) { reportError("UI_UNAVAILABLE"); return; }
            long generation = bindingGeneration;
            try {
                Snapshot result = call(UiStateContract.SET_LANGUAGE, language, null);
                adopt(result, generation);
            } catch (Exception refused) { reportError(errorCode(refused)); }
        } });
    }
    @Override public void close() {
        if (closed) return;
        closed = true;
        active = false;
        main.post(new Runnable() { public void run() { expiry.stop(); } });
        observers.clear();
        worker.post(new Runnable() { public void run() { started = false; worker.removeCallbacksAndMessages(null); disconnect(); thread.quitSafely(); } });
    }
    private void connect() {
        if (!started || closed || bound) return;
        try {
            controllerUid = UiStateIdentity.controller(context);
            final long generation = ++bindingGeneration;
            connection = new ServiceConnection() {
                @Override public void onServiceConnected(ComponentName name, IBinder service) {
                    worker.post(new Runnable() { public void run() { connected(name, service, generation); } });
                }
                @Override public void onServiceDisconnected(ComponentName name) { worker.post(new Runnable() { public void run() { lost(generation); } }); }
                @Override public void onBindingDied(ComponentName name) { worker.post(new Runnable() { public void run() { lost(generation); } }); }
                @Override public void onNullBinding(ComponentName name) { worker.post(new Runnable() { public void run() { lost(generation); } }); }
            };
            bound = context.bindService(new Intent().setComponent(UiStateContract.SERVICE), connection, Context.BIND_AUTO_CREATE);
            if (!bound) { connection = null; controllerUid = -1; retry(); }
        } catch (Exception refused) { reportError("UI_CONTROLLER_UNAVAILABLE"); retry(); }
    }
    private void connected(ComponentName name, IBinder service, long generation) {
        if (generation != bindingGeneration || !started || closed) return;
        try {
            if (!UiStateContract.SERVICE.equals(name) || UiStateIdentity.controller(context) != controllerUid ||
                    !UiStateContract.DESCRIPTOR.equals(service.getInterfaceDescriptor())) throw new SecurityException();
            remote = service;
            callback = new ObserverBinder(generation, controllerUid);
            adopt(call(UiStateContract.OBSERVE, null, callback), generation);
            retryDelay = 250;
        } catch (Exception refused) { reportError("UI_CONTROLLER_REFUSED"); disconnect(); retry(); }
    }
    private void lost(long generation) {
        if (generation != bindingGeneration) return;
        disconnect();
        retry();
    }
    private void retry() {
        if (!started || closed) return;
        worker.removeCallbacks(reconnect);
        worker.postDelayed(reconnect, retryDelay);
        retryDelay = Math.min(5000, retryDelay * 2);
    }
    private final Runnable reconnect = new Runnable() { public void run() { connect(); } };
    private void disconnect() {
        ++bindingGeneration;
        IBinder old = remote;
        ObserverBinder oldCallback = callback;
        remote = null; callback = null; controllerUid = -1;
        if (old != null && oldCallback != null && old.isBinderAlive()) {
            Parcel data = Parcel.obtain(), reply = Parcel.obtain();
            try {
                data.writeInterfaceToken(UiStateContract.DESCRIPTOR); data.writeStrongBinder(oldCallback);
                old.transact(UiStateContract.UNOBSERVE, data, reply, 0);
            } catch (Exception ignored) {} finally { data.recycle(); reply.recycle(); }
        }
        if (bound && connection != null) try { context.unbindService(connection); } catch (Exception ignored) {}
        bound = false; connection = null;
        publish(Snapshot.copy(snapshot, false));
    }
    private Snapshot call(int code, String language, IBinder observer) throws RemoteException {
        IBinder service = remote;
        if (service == null) throw new RemoteException("UI_UNAVAILABLE");
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(UiStateContract.DESCRIPTOR);
            if (code == UiStateContract.SET_LANGUAGE) data.writeString(language);
            if (code == UiStateContract.OBSERVE) data.writeStrongBinder(observer);
            if (!service.transact(code, data, reply, 0) || reply.dataSize() > UiStateContract.MAX_PARCEL_BYTES)
                throw new RemoteException("UI_INVALID_REPLY");
            reply.readException();
            int result = reply.readInt();
            if (result != UiStateContract.OK) throw new RemoteException("UI_ERROR_" + result);
            Snapshot next = Snapshot.copy(UiStateSnapshot.readFrom(reply), true);
            if (reply.dataAvail() != 0) throw new RemoteException("UI_INVALID_REPLY");
            return next;
        } finally { data.recycle(); reply.recycle(); }
    }
    private void adopt(Snapshot next, long generation) {
        if (generation != bindingGeneration || !started || closed || next.revision < snapshot.revision && snapshot.connected) return;
        UiLanguage.acceptConfirmed(context, next.language);
        publish(next);
    }
    private void publish(Snapshot next) {
        snapshot = next;
        notifyLatest(false);
    }
    private void notifyLatest(boolean expired) {
        synchronized (notificationLock) {
            expiryNotification |= expired;
            if (notificationQueued) return;
            notificationQueued = true;
        }
        main.post(new Runnable() { public void run() {
            final boolean copyForExpiry;
            synchronized (notificationLock) {
                notificationQueued = false; copyForExpiry = expiryNotification; expiryNotification = false;
            }
            Snapshot latest = snapshot;
            if (active && !closed) expiry.observe(latest); else expiry.stop();
            // A new object makes a time-only transition observable by Compose, while canonical
            // producer revision, source generation and observation remain untouched.
            Snapshot display = copyForExpiry ? Snapshot.copy(latest, latest.connected) : latest;
            if (!closed) for (Observer observer : observers) observer.onUiStateChanged(display);
        } });
    }
    private void reportError(String code) {
        main.post(new Runnable() { public void run() { if (!closed) for (Observer observer : observers) observer.onUiError(code); } });
    }
    private static String errorCode(Exception failure) {
        String code = failure.getMessage();
        return code != null && code.matches("UI_ERROR_[0-9]+") ? code : "UI_UNAVAILABLE";
    }
    private final class ObserverBinder extends Binder {
        private final long generation;
        private final int uid;
        private Snapshot pending;
        private boolean queued;
        private final Runnable deliver = new Runnable() { public void run() {
            Snapshot next;
            synchronized (ObserverBinder.this) { next = pending; pending = null; queued = false; }
            if (next != null) adopt(next, generation);
        } };
        ObserverBinder(long generation, int uid) { this.generation = generation; this.uid = uid; }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == INTERFACE_TRANSACTION) { if (reply != null) reply.writeString(UiStateContract.CALLBACK_DESCRIPTOR); return true; }
            // Authenticate the actual Binder UID before reading any caller-controlled field.
            if (Binder.getCallingUid() != uid || UiStateIdentity.controller(context) != uid) return false;
            if (code != UiStateContract.CHANGED || data.dataSize() > UiStateContract.MAX_PARCEL_BYTES) return false;
            try {
                data.enforceInterface(UiStateContract.CALLBACK_DESCRIPTOR);
                Snapshot next = Snapshot.copy(UiStateSnapshot.readFrom(data), true);
                if (data.dataAvail() != 0) return false;
                synchronized (this) {
                    if (pending != null && pending.revision > next.revision) return true;
                    pending = next;
                    if (!queued) { queued = true; worker.post(deliver); }
                }
                return true;
            } catch (RuntimeException malformed) { return false; }
        }
    }
}
