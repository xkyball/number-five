package com.no5.ui;

import android.graphics.drawable.Drawable;
import android.widget.TextView;

/** Public API29 styling only; retain Android's existing cursor/handle shapes and hit geometry. */
public final class No5TextSelection {
    private No5TextSelection(){}
    private static Drawable tint(Drawable value,int accent){if(value==null)return null;value=value.mutate();value.setTint(accent);return value;}
    public static void install(TextView view,int accent){
        view.setHighlightColor((accent&0x00ffffff)|0x55000000);
        view.setTextSelectHandle(tint(view.getTextSelectHandle(),accent));
        view.setTextSelectHandleLeft(tint(view.getTextSelectHandleLeft(),accent));
        view.setTextSelectHandleRight(tint(view.getTextSelectHandleRight(),accent));
        view.setTextCursorDrawable(tint(view.getTextCursorDrawable(),accent));
    }
}
