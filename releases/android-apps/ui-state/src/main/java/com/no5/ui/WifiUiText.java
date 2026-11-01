package com.no5.ui;

import android.content.Context;

/** Canonical local-WLAN text shared by native headers and Control's information dialog. */
public final class WifiUiText {
    private WifiUiText() { }
    public static String summary(Context context, WifiSignal signal) {
        return summary(UiLanguage.current(context), signal);
    }
    public static String details(Context context, WifiSignal signal) {
        return details(UiLanguage.current(context), signal);
    }
    /** Explicit language overloads allow Android-independent catalogue/format tests. */
    public static String summary(String language, WifiSignal signal) {
        WifiSignal value = signal == null ? WifiSignal.unknown() : signal;
        switch (value.state) {
            case OFF: return No5Text.text(language, "WLAN aus");
            case DISCONNECTED: return No5Text.text(language, "WLAN nicht verbunden");
            case CONNECTED:
                return value.rssiDbm == null ? No5Text.text(language, "WLAN verbunden · Signal unbekannt") :
                    No5Text.text(language, "WLAN verbunden · %d/4", value.bars);
            default: return No5Text.text(language, "WLAN unbekannt");
        }
    }
    public static String details(String language, WifiSignal signal) {
        WifiSignal value = signal == null ? WifiSignal.unknown() : signal;
        String strength = value.rssiDbm == null ? No5Text.text(language, "RSSI: unbekannt") :
            No5Text.text(language, "RSSI: %d dBm", value.rssiDbm);
        return summary(language, value) + "\n" + strength + "\n" +
            No5Text.text(language, "Lokaler Funkempfang · keine Aussage über Internet oder Gateway");
    }
}
