package com.no5.releaseinfo;

import android.content.Context;
import java.lang.reflect.Method;

/** Optional UI adapter: independently archived apps need no dependency on the new UI Binder. */
final class ReleaseUiText {
    private static volatile Method translator;
    private static volatile boolean resolved;
    private ReleaseUiText() {}
    static String text(Context context, String source) {
        if (!resolved) synchronized (ReleaseUiText.class) {
            if (!resolved) {
                try { translator = Class.forName("com.no5.ui.No5Text").getMethod("text", Context.class, String.class, Object[].class); }
                catch (ReflectiveOperationException unavailable) { translator = null; }
                resolved = true;
            }
        }
        Method method = translator;
        if (method != null) try { return (String) method.invoke(null, context, source, new Object[0]); }
        catch (ReflectiveOperationException ignored) { /* Optional adapter missing in older products. */ }
        return source;
    }
}
