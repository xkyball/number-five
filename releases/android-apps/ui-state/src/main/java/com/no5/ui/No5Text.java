package com.no5.ui;

import android.content.Context;
import java.util.Locale;
import java.util.Set;

/** UI-only exact-key translations. Never apply this API to transport payloads or user data. */
public final class No5Text {
    private static final Locale GERMAN = Locale.GERMAN;
    private static final Locale ENGLISH = Locale.ENGLISH;
    private static final Locale AFRIKAANS = Locale.forLanguageTag("af");
    private No5Text() {}

    public static String text(Context context, String sourceText) {
        return text(UiLanguage.current(context), sourceText);
    }

    public static String text(Context context, String sourceText, Object... arguments) {
        return text(UiLanguage.current(context), sourceText, arguments);
    }

    /** Process-cached language for UI helpers; app roots observe locale revisions for invalidation. */
    public static String ui(String sourceText) {
        return text(UiLanguage.current(), sourceText);
    }

    public static String ui(String sourceText, Object... arguments) {
        return text(UiLanguage.current(), sourceText, arguments);
    }

    /** Explicit language is also usable by native scenes and Android-independent label tests. */
    public static String text(String language, String sourceText) {
        return sourceText == null ? "" : UiTranslations.lookup(language, sourceText);
    }

    public static String text(String language, String sourceText, Object... arguments) {
        if (sourceText == null) return "";
        String translated = UiTranslations.lookup(language, sourceText);
        if (arguments == null || arguments.length == 0) return translated;
        // The common UI prefixes use %s/%d. Avoid constructing Formatter and its
        // regex machinery on every native draw; floating point/width formats
        // still use Java's locale-aware formatter outside input admission.
        boolean simple = true;
        int argument = 0;
        for (int index = 0; index < translated.length(); index++) {
            if (translated.charAt(index) != '%') continue;
            if (++index >= translated.length()) { simple = false; break; }
            char conversion = translated.charAt(index);
            if (conversion == '%') continue;
            if ((conversion != 's' && conversion != 'd') || argument >= arguments.length) { simple = false; break; }
            Object value = arguments[argument++];
            if (conversion == 'd' && value != null && !(value instanceof Byte || value instanceof Short ||
                    value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)) {
                simple = false; break;
            }
        }
        if (!simple) return String.format(locale(language), translated, arguments);
        StringBuilder result = new StringBuilder(translated.length() + Math.min(128, arguments.length * 8));
        int start = 0;
        argument = 0;
        for (int index = 0; index < translated.length(); index++) {
            if (translated.charAt(index) != '%') continue;
            result.append(translated, start, index);
            char conversion = translated.charAt(++index);
            if (conversion == '%') result.append('%');
            else result.append(arguments[argument++]);
            start = index + 1;
        }
        return result.append(translated, start, translated.length()).toString();
    }

    public static Locale locale(String language) {
        return "en".equals(language) ? ENGLISH : "af".equals(language) ? AFRIKAANS : GERMAN;
    }

    public static String languageName(String language) {
        if ("en".equals(language)) return "English";
        if ("af".equals(language)) return "Afrikaans";
        return "Deutsch";
    }

    public static String[] languages() { return new String[] { "de", "en", "af" }; }
    public static boolean hasTranslation(String sourceText) { return UiTranslations.contains(sourceText); }
    public static Set<String> translationKeys() { return UiTranslations.keys(); }
}
