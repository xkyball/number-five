package com.no5.ui;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Locale;

/** App-only language; never changes Android's device locale or recreates an Activity. */
public final class UiLanguage {
    public static final String DEFAULT = "de";
    public static final String PREFERENCES = "no5-ui-state-v1";
    public static final String LANGUAGE_KEY = "language";
    private static volatile String cachedLanguage;
    private static SharedPreferences preferences;
    private static final SharedPreferences.OnSharedPreferenceChangeListener CHANGES =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override public void onSharedPreferenceChanged(SharedPreferences source, String key) {
                    if (LANGUAGE_KEY.equals(key)) cachedLanguage = normalized(source.getString(LANGUAGE_KEY, DEFAULT));
                }
            };
    private UiLanguage() {}
    public static boolean supported(String tag) {
        return "de".equals(tag) || "en".equals(tag) || "af".equals(tag);
    }
    public static String requireSupported(String tag) {
        if (!supported(tag)) throw new IllegalArgumentException("UNSUPPORTED_LANGUAGE");
        return tag;
    }
    public static Locale locale(String tag) { return Locale.forLanguageTag(requireSupported(tag)); }
    public static String label(String tag) {
        switch (requireSupported(tag)) {
            case "en": return "English";
            case "af": return "Afrikaans";
            default: return "Deutsch";
        }
    }
    public static String current(Context context) {
        String value = cachedLanguage;
        if (value != null) return value;
        synchronized (UiLanguage.class) {
            if (preferences == null) {
                preferences = context.getApplicationContext().getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
                preferences.registerOnSharedPreferenceChangeListener(CHANGES);
            }
            if (cachedLanguage == null) cachedLanguage = normalized(preferences.getString(LANGUAGE_KEY, DEFAULT));
            return cachedLanguage;
        }
    }
    /** Render helpers use the already initialized cache, without resolving Context or preferences. */
    public static String current() {
        String value = cachedLanguage;
        return value == null ? DEFAULT : value;
    }
    /** Called only for an acknowledged server snapshot, before notifying UI observers. */
    public static void acceptConfirmed(Context context, String language) {
        requireSupported(language);
        current(context);
        synchronized (UiLanguage.class) {
            if (language.equals(cachedLanguage)) return;
            cachedLanguage = language;
            preferences.edit().putString(LANGUAGE_KEY, language).apply();
        }
    }
    private static String normalized(String value) { return supported(value) ? value : DEFAULT; }
}
