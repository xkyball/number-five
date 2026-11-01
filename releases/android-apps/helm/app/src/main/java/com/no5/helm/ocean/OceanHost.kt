package com.no5.helm.ocean

import android.animation.ValueAnimator
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Host reads provider state outside draw; only the retained background draw reads returned State. */
@Composable
fun rememberOceanFrame(inputs: OceanInputs, mode: BackgroundMode, night: Boolean, helmVisible: Boolean,
    renderer: OceanBackgroundRenderer, quality: OceanQuality, reducedMotion: Boolean,
    hardwareAccelerated: Boolean, meshRendererAvailable: Boolean = false): State<OceanFrame> {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val mapper = remember(inputs.sessionId) { OceanPresentation(inputs.sessionId) }
    val clock = remember(inputs.sessionId) { OceanClock() }
    val result = remember(inputs.sessionId) { mutableStateOf(OceanFrame()) }
    var shaderReady by remember(renderer) { mutableStateOf(renderer.shaderAvailable) }
    val visible = helmVisible && resumed && !night
    val prepareAnimation = visible && mode == BackgroundMode.ANIMATION && !reducedMotion &&
        hardwareAccelerated && quality != OceanQuality.STATIC
    LaunchedEffect(renderer, mode, visible, prepareAnimation) {
        if (visible && mode != BackgroundMode.NONE) {
            renderer.prepare(if (prepareAnimation) BackgroundMode.ANIMATION else BackgroundMode.IMAGE)
            // A static IMAGE also needs one draw invalidation after its first asynchronous setup.
            result.value = result.value.copy(preparationRevision = result.value.preparationRevision + 1)
        }
        shaderReady = renderer.shaderAvailable
    }
    val run = OceanVisibility(helmVisible,resumed,night,reducedMotion,mode)
        .runs(quality, (shaderReady || meshRendererAvailable) && hardwareAccelerated)
    // Accept before activation, then publish its current orientation before the first resumed draw.
    // Keep the paused visual phase; ordinary frames and HIGH/LOW changes retain interpolation.
    SideEffect {
        mapper.accept(inputs)
        val previous = result.value
        result.value = if (mapper.setAnimationActive(run)) {
            mapper.frame(previous.timeSeconds, 0f, quality).copy(preparationRevision = previous.preparationRevision)
        } else previous.copy(quality = quality)
    }
    LaunchedEffect(run, mapper, quality, renderer, meshRendererAvailable) {
        clock.pause()
        val cadence = OceanFrameCadence()
        if (run) {
            while (renderer.shaderAvailable || meshRendererAvailable) {
                withFrameNanos { frameNanos ->
                    if (cadence.shouldPublish(frameNanos, quality)) {
                        val (seconds,dt) = clock.tick(System.nanoTime(), result.value.waveSpeed)
                        result.value = mapper.frame(seconds,dt,quality).copy(preparationRevision = result.value.preparationRevision)
                    }
                }
            }
            shaderReady = false
        }
    }
    DisposableEffect(clock) { onDispose { clock.pause() } }
    return result
}

/** Custom frame loops explicitly honor the system animator switch and react while the app is open. */
@Composable
fun rememberOceanReducedMotion(explicitReducedMotion: Boolean = false): Boolean {
    val resolver = LocalContext.current.contentResolver
    var systemEnabled by remember { mutableStateOf(ValueAnimator.areAnimatorsEnabled()) }
    DisposableEffect(resolver) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { systemEnabled = ValueAnimator.areAnimatorsEnabled() }
        }
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE),false,observer)
        onDispose { resolver.unregisterContentObserver(observer) }
    }
    return explicitReducedMotion || !systemEnabled
}
