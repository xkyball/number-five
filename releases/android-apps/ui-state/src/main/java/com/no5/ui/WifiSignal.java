package com.no5.ui;

import java.util.Objects;

/** Local radio observation; it does not claim Internet or gateway reachability. */
public final class WifiSignal {
    public enum State { OFF, DISCONNECTED, UNKNOWN, CONNECTED }
    public final State state;
    /** Actual Android RSSI in dBm, or null when the platform cannot provide it. */
    public final Integer rssiDbm;
    /** Common five-level scale, or -1 when there is no confirmed RSSI. */
    public final int bars;

    private WifiSignal(State state, Integer rssiDbm) {
        this.state = Objects.requireNonNull(state);
        this.rssiDbm = state == State.CONNECTED && validRssi(rssiDbm) ? rssiDbm : null;
        this.bars = this.rssiDbm == null ? -1 : barsForRssi(this.rssiDbm);
    }
    public static WifiSignal off() { return new WifiSignal(State.OFF, null); }
    public static WifiSignal disconnected() { return new WifiSignal(State.DISCONNECTED, null); }
    public static WifiSignal unknown() { return new WifiSignal(State.UNKNOWN, null); }
    public static WifiSignal connected(Integer rssiDbm) { return new WifiSignal(State.CONNECTED, rssiDbm); }

    /** A disabled radio overrides an older connection; missing evidence never implies disconnected. */
    public static WifiSignal fromObservation(Boolean enabled, Boolean connected, Integer rssiDbm) {
        if (Boolean.FALSE.equals(enabled)) return off();
        if (Boolean.TRUE.equals(connected)) return connected(rssiDbm);
        if (Boolean.TRUE.equals(enabled) && Boolean.FALSE.equals(connected)) return disconnected();
        return unknown();
    }
    public static boolean validRssi(Integer rssiDbm) {
        // Android's unavailable sentinel is -127; non-negative values are not a measured WLAN RSSI.
        return rssiDbm != null && rssiDbm > -127 && rssiDbm < 0;
    }
    public static int barsForRssi(int rssiDbm) {
        if (!validRssi(rssiDbm)) return -1;
        // Fixed across apps and Android/OEM versions: -100→0, -55→4, linear intervals in between.
        if (rssiDbm <= -100) return 0;
        if (rssiDbm >= -55) return 4;
        return (rssiDbm + 100) * 4 / 45;
    }
    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof WifiSignal)) return false;
        WifiSignal value = (WifiSignal) other;
        return state == value.state && Objects.equals(rssiDbm, value.rssiDbm);
    }
    @Override public int hashCode() { return Objects.hash(state, rssiDbm); }
    @Override public String toString() { return "WifiSignal(" + state + ", rssiDbm=" + rssiDbm + ", bars=" + bars + ")"; }
}
