package com.no5.ui;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Identical local-radio glyph in the three headers and native Control setup. */
public final class WifiSignalDrawable extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private WifiSignal signal = WifiSignal.unknown();
    private int color = 0xff122653;
    public void render(WifiSignal value, int ink) {
        signal = value == null ? WifiSignal.unknown() : value; color = ink; invalidateSelf();
    }
    @Override public void draw(Canvas canvas) {
        int saved = canvas.save();
        float scale = Math.min(getBounds().width()/96f, getBounds().height()/96f);
        canvas.translate(getBounds().centerX()-48*scale,getBounds().centerY()-48*scale); canvas.scale(scale,scale);
        paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(3);
        for (int index=0;index<4;index++) {
            paint.setAlpha(signal.state==WifiSignal.State.CONNECTED && signal.bars>index ? 255 : 55);
            float radius=10+index*9; arc.set(48-radius,68-radius,48+radius,68+radius);
            canvas.drawArc(arc,218,104,false,paint);
        }
        paint.setAlpha(255);paint.setStyle(Paint.Style.FILL);canvas.drawCircle(48,66,2.5f,paint);
        String marker = signal.state==WifiSignal.State.OFF ? "×" :
            signal.state==WifiSignal.State.DISCONNECTED ? "–" :
            signal.state==WifiSignal.State.UNKNOWN || signal.bars<0 ? "?" : "";
        paint.setTextAlign(Paint.Align.CENTER);paint.setTextSize(24);
        canvas.drawText(marker,76,77,paint);canvas.restoreToCount(saved);
    }
    @Override public void setAlpha(int alpha) { /* Bars encode their own confirmed/unknown alpha. */ }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
