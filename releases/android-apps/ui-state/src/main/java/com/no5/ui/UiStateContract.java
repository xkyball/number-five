package com.no5.ui;

import android.content.ComponentName;
import android.os.IBinder;

/** Independent UI-only wire contract. Contains no credentials, gateway roles or knob events. */
public final class UiStateContract {
    public static final int VERSION = 1;
    public static final String DESCRIPTOR = "com.no5.lumina.control.IUiStateV1";
    public static final String CALLBACK_DESCRIPTOR = "com.no5.ui.IUiStateObserverV1";
    public static final String PERMISSION = "com.no5.lumina.control.permission.UI_STATE";
    public static final String CONTROLLER = "com.no5.lumina.control";
    public static final ComponentName SERVICE = new ComponentName(CONTROLLER,
            "com.no5.lumina.control.UiStateService");
    public static final int READ = IBinder.FIRST_CALL_TRANSACTION;
    public static final int SET_LANGUAGE = READ + 1;
    public static final int OBSERVE = READ + 2;
    public static final int UNOBSERVE = READ + 3;
    public static final int CHANGED = IBinder.FIRST_CALL_TRANSACTION;
    public static final int MAX_PARCEL_BYTES = 16384;
    public static final int MAX_OBSERVERS = 16;
    public static final int MAX_TEXT_CHARS = 512;
    public static final long MEDIA_FRESH_MS = 5000;
    public static final int OK = 0, CALLER_DENIED = 1, MALFORMED_REQUEST = 2,
            UNSUPPORTED_LANGUAGE = 3, OBSERVER_LIMIT = 4, STORAGE_FAILED = 5;
    private UiStateContract() {}
}
