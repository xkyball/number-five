package com.no5.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.util.LinkedHashSet;
import java.util.Set;

/** One passive, process-local WLAN observer. No Binder, scan, SSID, BSSID, IP or network probe. */
public final class WifiObserver {
    public interface Listener { void onWifiSignal(WifiSignal value); }
    /** Own this only while the consumer is visible; refresh on resume, close on pause/stop. */
    public interface Subscription extends AutoCloseable {
        void refresh();
        @Override void close();
    }
    private static WifiObserver instance;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ConnectivityManager connectivity;
    private final WifiManager wifi;
    private final WifiSignalMonitor monitor;
    private ConnectivityManager.NetworkCallback callback;
    private BroadcastReceiver receiver;
    private int generation;
    private final Set<Network> networks = new LinkedHashSet<Network>();
    private boolean initialSnapshotPending = true;
    private boolean networkOverflow;

    private WifiObserver(Context application) {
        app = application;
        connectivity = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        wifi = (WifiManager) app.getSystemService(Context.WIFI_SERVICE);
        monitor = new WifiSignalMonitor(new WifiSignalMonitor.Clock() {
            @Override public long elapsedMs() { return SystemClock.elapsedRealtime(); }
        }, new WifiSignalMonitor.Scheduler() {
            @Override public void schedule(Runnable task, long delayMs) { main.postDelayed(task, delayMs); }
            @Override public void cancel(Runnable task) { main.removeCallbacks(task); }
        }, new WifiSignalMonitor.Backend() {
            @Override public void start() { startWatching(); }
            @Override public void stop() { stopWatching(); }
            @Override public WifiSignal read() { return readCurrent(); }
        });
    }
    public static Subscription subscribe(Context context, final Listener listener) {
        requireMain();
        if (context == null || listener == null) throw new NullPointerException("context/listener");
        if (instance == null) {
            Context application = context.getApplicationContext();
            if (application == null) throw new IllegalArgumentException("Application context is required");
            instance = new WifiObserver(application);
        }
        final WifiSignalMonitor.Handle handle = instance.monitor.subscribe(new WifiSignalMonitor.Listener() {
            @Override public void changed(WifiSignal value) { listener.onWifiSignal(value); }
        });
        return new Subscription() {
            @Override public void refresh() { requireMain(); handle.refresh(); }
            @Override public void close() { requireMain(); handle.close(); }
        };
    }
    private void startWatching() {
        final int epoch = ++generation;
        if (connectivity != null) {
            ConnectivityManager.NetworkCallback candidate = new ConnectivityManager.NetworkCallback() {
                private void changed() { if (generation == epoch) monitor.refresh(); }
                @Override public void onAvailable(Network network) {
                    if (generation == epoch) remember(network);
                    changed();
                }
                @Override public void onLost(Network network) {
                    if (generation == epoch) networks.remove(network);
                    changed();
                }
                @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                    if (generation == epoch) remember(network);
                    changed();
                }
            };
            try {
                // Observe existing underlying WLAN, including local-only networks, even when default is VPN.
                NetworkRequest request = new NetworkRequest.Builder()
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
                connectivity.registerNetworkCallback(request, candidate, main);
                callback = candidate;
            } catch (RuntimeException unavailable) { /* periodic read still reports only actual observations */ }
        }
        BroadcastReceiver candidate = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) {
                if (generation == epoch) monitor.refresh();
            }
        };
        try {
            // Both actions are protected system broadcasts, so the API29 registration also works on target35.
            IntentFilter filter = new IntentFilter(WifiManager.WIFI_STATE_CHANGED_ACTION);
            filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
            app.registerReceiver(candidate, filter, null, main);
            receiver = candidate;
        } catch (RuntimeException unavailable) { /* a missing permission never becomes a fabricated state */ }
    }
    private void stopWatching() {
        generation++;
        if (callback != null && connectivity != null) {
            try { connectivity.unregisterNetworkCallback(callback); } catch (RuntimeException ignored) { }
        }
        callback = null;
        if (receiver != null) {
            try { app.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
        }
        receiver = null;
        networks.clear(); initialSnapshotPending = true; networkOverflow = false;
    }
    private void remember(Network network) {
        if (networks.size() < 16 || networks.contains(network)) networks.add(network);
        else networkOverflow = true;
    }
    @SuppressWarnings("deprecation")
    private WifiSignal readCurrent() {
        Boolean enabled = null;
        if (wifi != null) {
            try {
                int state = wifi.getWifiState();
                if (state == WifiManager.WIFI_STATE_DISABLED) enabled = Boolean.FALSE;
                else if (state == WifiManager.WIFI_STATE_ENABLED) enabled = Boolean.TRUE;
            } catch (RuntimeException unavailable) { }
        }
        if (Boolean.FALSE.equals(enabled)) return WifiSignal.off();
        if (connectivity == null) return WifiSignal.unknown();
        boolean connected = false;
        Integer rssi = null;
        try {
            // One resume snapshot closes callback-registration races; later membership is callback driven.
            // Deliberately not getActiveNetwork(): that can name a VPN rather than its physical WLAN.
            if (initialSnapshotPending || callback == null) {
                for (Network network : connectivity.getAllNetworks()) {
                    NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                    if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                            !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) remember(network);
                }
                initialSnapshotPending = false;
            }
            if (networkOverflow) return WifiSignal.unknown();
            for (Network network : networks.toArray(new Network[0])) {
                NetworkCapabilities caps = connectivity.getNetworkCapabilities(network);
                if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { networks.remove(network); continue; }
                connected = true;
                if (caps.getTransportInfo() instanceof WifiInfo) {
                    int actual = ((WifiInfo) caps.getTransportInfo()).getRssi();
                    if (WifiSignal.validRssi(actual) && (rssi == null || actual > rssi)) rssi = actual;
                }
            }
        } catch (RuntimeException unavailable) { return WifiSignal.fromObservation(enabled, null, null); }
        // API29/30 expose primary-WLAN RSSI through WifiManager; API31+ uses the non-location transport info.
        if (connected && rssi == null && wifi != null && Build.VERSION.SDK_INT < 31) {
            try {
                WifiInfo info = wifi.getConnectionInfo();
                if (info != null && WifiSignal.validRssi(info.getRssi())) rssi = info.getRssi();
            } catch (RuntimeException unavailable) { }
        }
        return WifiSignal.fromObservation(enabled, connected, rssi);
    }
    private static void requireMain() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("WLAN observation requires main thread");
    }
}
