package com.no5.helm.ocean.gl

import android.graphics.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.helm.HelmScene
import com.no5.helm.ocean.*

/** Persistent host status: a terminal GL failure is never retried on every frame/navigation. */
class OceanGlState {
    var ready by mutableStateOf(false); internal set
    var failed by mutableStateOf(false); internal set
    var diagnostic by mutableStateOf<String?>(null); internal set
}

@Composable
fun oceanGlEligible(mode: BackgroundMode, night: Boolean, reducedMotion: Boolean,
    hardware: Boolean, quality: OceanQuality, status: OceanGlState): Boolean {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver { _,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return resumed && !night && !reducedMotion && hardware && !status.failed &&
        mode==BackgroundMode.ANIMATION && quality!=OceanQuality.STATIC
}

@Composable
fun OceanGlLayer(scene: HelmScene,frame: State<OceanFrame>,status: OceanGlState,scale: Float) {
    // Copy on the UI thread. The GL thread only reads this private immutable snapshot.
    val aperture=remember(scene) { scene.oceanAperture() }
    var view by remember { mutableStateOf<OceanTextureView?>(null) }
    DisposableEffect(status) { onDispose { status.ready=false } }
    AndroidView(modifier=Modifier.offset((400*scale).dp,(44*scale).dp).size((1200*scale).dp,(704*scale).dp),
        factory={ context ->
            OceanTextureView(context,{w,h->oceanMask(aperture,w,h)},
                {status.ready=it},{status.failed=true;status.diagnostic=it}).also { view=it }
        },onRelease={it.dispose();view=null},update={})
    LaunchedEffect(view,frame) {
        // A single immutable latest-value mailbox, no UI recomposition or unbounded frame queue.
        snapshotFlow { frame.value }.collect { view?.submit(it) }
    }
}

internal fun oceanMask(aperture: Path,width: Int,height: Int): Bitmap {
    val result=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
    val canvas=Canvas(result)
    canvas.scale(width/1200f,height/704f); canvas.translate(-400f,0f)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
        shader=LinearGradient(400f,0f,1600f,0f,intArrayOf(Color.TRANSPARENT,Color.WHITE,Color.WHITE,Color.TRANSPARENT),
            floatArrayOf(0f,.08f,.92f,1f),Shader.TileMode.CLAMP)
    }
    canvas.drawPath(aperture,paint)
    return result
}
