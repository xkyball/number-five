package com.no5.helm

import android.app.Activity
import android.util.Log
import android.view.View
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Event-driven fullscreen restoration. Never fights a picker or retries against OEM policy. */
internal class ImmersiveWindow(private val activity: Activity) {
    private val decor = activity.window.decorView
    private var resumed = false
    private var closed = false
    private var reason = "attach"
    private var lastInsets: String? = null
    private val sample = Runnable { diagnose("settled:$reason") }
    private val apply = Runnable {
        if (!closed && resumed && decor.isAttachedToWindow && decor.hasWindowFocus()) {
            diagnose("before:$reason")
            claimFullscreen()
            // On API29 this restores all three layout bits, including LAYOUT_HIDE_NAVIGATION.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            WindowCompat.getInsetsController(activity.window, decor).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                hide(WindowInsetsCompat.Type.systemBars())
            }
            diagnose("after:$reason")
            // One observation only; no retry/timer loop and no system-UI visibility listener.
            decor.removeCallbacks(sample)
            decor.postDelayed(sample, 250)
        }
    }
    private val attach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view: View) = request("attach")
        override fun onViewDetachedFromWindow(view: View) { cancelPending() }
    }
    init {
        // API29 OEM policy also checks window flags, not just DecorView visibility.
        claimFullscreen()
        decor.addOnAttachStateChangeListener(attach)
        ViewCompat.setOnApplyWindowInsetsListener(decor) { _, insets ->
            val bars = WindowInsetsCompat.Type.systemBars()
            // isVisible(systemBars()) requires *every* included type to be visible. API29
            // has no caption bar, so check the actual status/navigation bars separately.
            val barsVisible = insets.isVisible(WindowInsetsCompat.Type.statusBars()) ||
                insets.isVisible(WindowInsetsCompat.Type.navigationBars())
            val claimFlags = activity.window.attributes.flags and
                (WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN)
            val signature = "$claimFlags:$barsVisible:${insets.getInsets(bars)}"
            if (signature != lastInsets) {
                lastInsets = signature
                if (barsVisible && resumed && decor.hasWindowFocus()) request("insets")
            }
            // Pass through cutout, IME and bar insets to the Compose hierarchy.
            insets
        }
    }

    private fun claimFullscreen() {
        val window=activity.window
        val mask=WindowManager.LayoutParams.FLAG_FULLSCREEN or WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN
        // Window.setFlags dispatches an attribute change even when the masked
        // value is unchanged. A resume/focus event must not relayout a correct
        // hardware Window merely to repeat the same claim.
        if(window.attributes.flags and mask != WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,mask)
        // Hardware masks belong to the physical window. Editors handle their own IME
        // occlusion; Android must neither resize nor pan the complete instrument stage.
        if(window.attributes.softInputMode != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
    }

    fun onResume() { resumed = true; request("resume") }
    fun onPause() { resumed = false; cancelPending() }
    fun onWindowFocusChanged(focused: Boolean) {
        if (focused) request("focus") else cancelPending()
    }
    private fun request(event: String) {
        if (closed) return
        reason = event
        decor.removeCallbacks(apply)
        if (decor.isAttachedToWindow) decor.post(apply)
    }
    private fun cancelPending() { decor.removeCallbacks(apply); decor.removeCallbacks(sample) }
    fun close() {
        closed = true; resumed = false; cancelPending()
        decor.removeOnAttachStateChangeListener(attach)
        ViewCompat.setOnApplyWindowInsetsListener(decor, null)
    }

    @Suppress("DEPRECATION")
    private fun diagnose(phase: String) {
        val insets = ViewCompat.getRootWindowInsets(decor)
        val content = decor.findViewById<View>(android.R.id.content)
        val bars = WindowInsetsCompat.Type.systemBars()
        Log.i("No5Fullscreen", "${BuildConfig.VERSION_NAME}/${BuildConfig.VERSION_CODE} $phase " +
            "focus=${decor.hasWindowFocus()} attached=${decor.isAttachedToWindow} resumed=$resumed " +
            "ui=0x${decor.systemUiVisibility.toString(16)} window=0x${activity.window.attributes.flags.toString(16)} " +
            "decor=${decor.width}x${decor.height} root=${content?.width}x${content?.height} " +
            "bars=${insets?.getInsets(bars)} stable=${insets?.getInsetsIgnoringVisibility(bars)} " +
            "statusVisible=${insets?.isVisible(WindowInsetsCompat.Type.statusBars())} " +
            "navVisible=${insets?.isVisible(WindowInsetsCompat.Type.navigationBars())}")
    }
}
