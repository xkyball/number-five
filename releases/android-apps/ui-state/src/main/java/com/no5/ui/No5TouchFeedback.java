package com.no5.ui;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;

/** Native counterpart of no5Clickable. Install only on ordinary click controls, not drag owners. */
public final class No5TouchFeedback {
    private static final Map<View, WeakReference<Binding>> BINDINGS = new WeakHashMap<>();
    private No5TouchFeedback() {}

    /** Preserve the neutral contents of an existing ripple, never its focused state-layer. */
    public static void install(View view, int pressColor, int focusColor) {
        install(view, neutralFace(view.getBackground()), pressColor, focusColor);
    }

    /** Supply a neutral face explicitly when the original background has custom focus styling. */
    public static void install(View view, Drawable neutralFace, int pressColor, int focusColor) {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("UI thread required");
        WeakReference<Binding> previous = BINDINGS.get(view);
        Binding existing = previous == null ? null : previous.get();
        if (existing != null) { existing.update(neutralFace, pressColor, focusColor); return; }
        Binding binding = new Binding(view, neutralFace, pressColor, focusColor);
        BINDINGS.put(view, new WeakReference<>(binding));
        view.setBackground(binding.face);
        view.setOnTouchListener(binding);
        view.addOnAttachStateChangeListener(binding);
        if (view.isAttachedToWindow()) binding.onViewAttachedToWindow(view);
    }

    private static Drawable neutralFace(Drawable original) {
        if (original instanceof Face) return ((Face) original).neutral;
        if (!(original instanceof RippleDrawable)) return original;
        RippleDrawable ripple = (RippleDrawable) original;
        ArrayList<Drawable> layers = new ArrayList<>();
        for (int i = 0; i < ripple.getNumberOfLayers(); i++)
            if (ripple.getId(i) != android.R.id.mask) layers.add(ripple.getDrawable(i));
        if (layers.isEmpty()) return new ColorDrawable(Color.TRANSPARENT);
        if (layers.size() == 1) return layers.get(0);
        return new LayerDrawable(layers.toArray(new Drawable[0]));
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private static final class Binding implements View.OnTouchListener, View.OnAttachStateChangeListener,
            ViewTreeObserver.OnWindowFocusChangeListener, ViewTreeObserver.OnGlobalLayoutListener,
            Application.ActivityLifecycleCallbacks {
        final View view;
        final Activity owner;
        Face face;
        boolean resumed = true, registered, pointerActive, cancelled;
        int pointerId = -1;
        long downTime;
        ViewTreeObserver observer;
        Binding(View view, Drawable neutralFace, int pressColor, int focusColor) {
            this.view = view;
            this.owner = activity(view.getContext());
            setFace(neutralFace, pressColor, focusColor);
        }
        private void setFace(Drawable neutralFace, int pressColor, int focusColor) {
            face = new Face(neutralFace, pressColor, focusColor, view.getResources().getDisplayMetrics().density);
            face.disabled = new Runnable() { @Override public void run() { Binding.this.cancel(); } };
        }
        void update(Drawable neutralFace, int pressColor, int focusColor) {
            cancel();
            setFace(neutralFace, pressColor, focusColor);
            view.setBackground(face);
            refresh();
        }
        boolean active() { return resumed && view.isAttachedToWindow() && view.isShown() && view.hasWindowFocus() && view.isEnabled(); }
        void refresh() { face.active = active(); if (!face.active) cancel(); face.invalidateSelf(); }
        void cancel() {
            if (pointerActive && !cancelled) {
                cancelled = true;
                MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0f, 0f, 0);
                try { view.onTouchEvent(event); } finally { event.recycle(); }
            }
            view.cancelLongPress();
            view.setPressed(false);
            face.invalidateSelf();
        }
        boolean outside(MotionEvent event) {
            int index = event.findPointerIndex(pointerId);
            if (index < 0) return true;
            if (event.getX(index) < 0 || event.getX(index) >= view.getWidth() || event.getY(index) < 0 || event.getY(index) >= view.getHeight()) return true;
            for (int h = 0; h < event.getHistorySize(); h++)
                if (event.getHistoricalX(index, h) < 0 || event.getHistoricalX(index, h) >= view.getWidth() ||
                    event.getHistoricalY(index, h) < 0 || event.getHistoricalY(index, h) >= view.getHeight()) return true;
            return false;
        }
        @Override public boolean onTouch(View target, MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pointerActive = true; cancelled = false; pointerId = event.getPointerId(0); downTime = event.getDownTime();
            }
            if (!active() || event.getPointerCount() != 1 || outside(event)) cancel();
            boolean consume = cancelled;
            if (action == MotionEvent.ACTION_CANCEL) { cancel(); consume = true; }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) { pointerActive = false; pointerId = -1; }
            return consume;
        }
        @Override public void onViewAttachedToWindow(View view) {
            observer = view.getViewTreeObserver();
            observer.addOnWindowFocusChangeListener(this); observer.addOnGlobalLayoutListener(this);
            if (owner != null && !registered) { owner.getApplication().registerActivityLifecycleCallbacks(this); registered = true; }
            refresh();
        }
        @Override public void onViewDetachedFromWindow(View view) {
            cancel(); face.active = false; pointerActive = false; pointerId = -1;
            if (observer != null && observer.isAlive()) { observer.removeOnWindowFocusChangeListener(this); observer.removeOnGlobalLayoutListener(this); }
            observer = null;
            if (owner != null && registered) { owner.getApplication().unregisterActivityLifecycleCallbacks(this); registered = false; }
        }
        @Override public void onWindowFocusChanged(boolean focused) { refresh(); }
        @Override public void onGlobalLayout() { refresh(); }
        @Override public void onActivityResumed(Activity activity) { if (activity == owner) { resumed = true; refresh(); } }
        @Override public void onActivityPaused(Activity activity) { if (activity == owner) { resumed = false; refresh(); } }
        @Override public void onActivityStopped(Activity activity) { if (activity == owner) { resumed = false; refresh(); } }
        @Override public void onActivityDestroyed(Activity activity) { if (activity == owner) onViewDetachedFromWindow(view); }
        @Override public void onActivityCreated(Activity activity, Bundle state) {}
        @Override public void onActivityStarted(Activity activity) {}
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
    }

    private static final class Face extends Drawable implements Drawable.Callback {
        final Drawable neutral;
        final int pressColor, focusColor;
        final float density;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boolean active, pressed, focused, enabled;
        Runnable disabled;
        Face(Drawable neutral, int pressColor, int focusColor, float density) {
            this.neutral = neutral; this.pressColor = pressColor; this.focusColor = focusColor; this.density = density;
            if (neutral != null) neutral.setCallback(this);
        }
        @Override public void draw(Canvas canvas) {
            if (neutral != null) { neutral.setBounds(getBounds()); neutral.draw(canvas); }
            if (!active || !enabled) return;
            RectF r = new RectF(getBounds());
            paint.setStyle(Paint.Style.FILL);
            // Callers may already supply a translucent native ripple tint; do not attenuate it twice.
            if (pressed) { paint.setColor(pressColor); paint.setAlpha(Math.min(Color.alpha(pressColor), Math.round(255 * .14f))); canvas.drawRoundRect(r, 4 * density, 4 * density, paint); }
            if (focused) { r.inset(density, density); paint.setColor(focusColor); paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * density); canvas.drawRoundRect(r, 4 * density, 4 * density, paint); }
        }
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] states) {
            boolean wasEnabled = enabled;
            enabled = false; pressed = false; focused = false;
            ArrayList<Integer> neutralStates = new ArrayList<>();
            for (int state : states) {
                if (state == android.R.attr.state_enabled) enabled = true;
                if (state == android.R.attr.state_pressed) pressed = true;
                else if (state == android.R.attr.state_focused) focused = true;
                else if (state != android.R.attr.state_hovered) neutralStates.add(state);
            }
            if (neutral != null) { int[] copy = new int[neutralStates.size()]; for (int i = 0; i < copy.length; i++) copy[i] = neutralStates.get(i); neutral.setState(copy); }
            if (wasEnabled && !enabled && disabled != null) disabled.run();
            invalidateSelf(); return true;
        }
        @Override public void setAlpha(int alpha) { if (neutral != null) neutral.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { if (neutral != null) neutral.setColorFilter(filter); }
        @Override public boolean getPadding(Rect padding) { return neutral != null && neutral.getPadding(padding); }
        @Override public int getIntrinsicWidth() { return neutral == null ? -1 : neutral.getIntrinsicWidth(); }
        @Override public int getIntrinsicHeight() { return neutral == null ? -1 : neutral.getIntrinsicHeight(); }
        @Override public int getMinimumWidth() { return neutral == null ? 0 : neutral.getMinimumWidth(); }
        @Override public int getMinimumHeight() { return neutral == null ? 0 : neutral.getMinimumHeight(); }
        @SuppressWarnings("deprecation") @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void invalidateDrawable(Drawable drawable) { invalidateSelf(); }
        @Override public void scheduleDrawable(Drawable drawable, Runnable what, long when) { scheduleSelf(what, when); }
        @Override public void unscheduleDrawable(Drawable drawable, Runnable what) { unscheduleSelf(what); }
    }
}
