package com.no5.helm.video

import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.helm.HelmScene
import com.no5.helm.ocean.*
import com.no5.helm.ocean.gl.oceanMask
import java.util.concurrent.atomic.AtomicLong

class VideoState {
    var ready by mutableStateOf(false); internal set
    var failed by mutableStateOf(false); internal set
    var diagnostic by mutableStateOf("Video wird bei HELM / DAY aktiviert."); internal set
    var revision by mutableIntStateOf(0); private set
    internal val positionUs=AtomicLong(0)
    private var sourceSession: String?=null
    private var presentation: OceanPresentation?=null
    internal fun mapperFor(session: String): OceanPresentation {
        if(sourceSession!=session){sourceSession=session;presentation=OceanPresentation(session)}
        return presentation!!
    }
    fun retry(){failed=false;ready=false;diagnostic="Video wird erneut geprüft.";revision++}
}
@Composable
fun videoEligible(helm: Boolean,night: Boolean,reduced: Boolean,hardware: Boolean,selected: Boolean,uri: String?,state: VideoState): Boolean {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var resumed by remember(lifecycle){mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle){
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose{lifecycle.removeObserver(observer)}
    }
    return VideoPolicy.visible(helm,resumed,night,reduced,hardware,selected,uri!=null,state.failed)
}
@Composable
fun VideoLayer(scene: HelmScene,inputs: OceanInputs,uri: String,state: VideoState,scale: Float){
    val mapper=remember(state,inputs.sessionId){state.mapperFor(inputs.sessionId)}
    val latestInputs by rememberUpdatedState(inputs)
    SideEffect {mapper.accept(inputs);mapper.setAnimationActive(true)}
    val aperture=remember(scene){scene.oceanAperture()}
    var view by remember {mutableStateOf<VideoTextureView?>(null)}
    DisposableEffect(state,mapper){onDispose{state.ready=false;mapper.setAnimationActive(false)}}
    AndroidView(modifier=Modifier.offset((400*scale).dp,(44*scale).dp).size((1200*scale).dp,(704*scale).dp),
        factory={context->VideoTextureView(context,uri,state.positionUs,{w,h->oceanMask(aperture,w,h)},
            {state.ready=it},{message,failed->state.diagnostic=message;state.failed=failed}).also{view=it}},
        onRelease={it.dispose();view=null},update={})
    LaunchedEffect(view,mapper){
        var last=0L
        while(true)withFrameNanos{now->
            val dt=if(last==0L)0f else ((now-last)/1e9).toFloat().coerceIn(0f,.05f);last=now
            val frame=mapper.frame(0f,dt,OceanQuality.HIGH)
            val input=latestInputs
            view?.submit(VideoViewFrame(frame.headingRadians,VideoPolicy.fog(input.visibilityMeters,input.weatherQuality==OceanInputQuality.LIVE)))
        }
    }
}
