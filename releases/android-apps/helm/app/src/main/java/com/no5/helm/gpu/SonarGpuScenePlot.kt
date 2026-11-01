package com.no5.helm.gpu
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.no5.domain.DepthUnit
import com.no5.helm.AudioNativeController
import com.no5.helm.SonarNativeController
import kotlinx.coroutines.flow.collect

/** Snapshot observation feeds the existing View without rebuilding its Compose hierarchy.
 * A command refers to the shared RAW ring; conflating commands never removes measurements. */
internal data class SonarSceneRequest(val frame:SonarGpuFrame,val ui:List<Any?>)
internal fun sonarSceneUi(controller:SonarNativeController,unit:DepthUnit,audio:AudioNativeController?):List<Any?> {
    val labels=com.no5.helm.sonarPanelPresentation(controller,unit)
    // Compare painted values, never publication counters, raw IDs, arrival
    // timestamps or the entire status snapshot. Those already drive the GPU command.
    return listOf(labels.sourceLabel,labels.depthLabel,labels.depthStatusLabel,
        labels.rangeLabel,labels.gainLabel,controller.rateTitle(),controller.rateLabel(),controller.selectedUsableRateLabel(),
        if(controller.model.viewRing.count==0)controller.sourceStatus.connected else null,controller.cursor,controller.drawer,
        controller.knobMode,controller.knobLeaseReason,controller.reliefReady,controller.reliefAligned,
        controller.viewportStatusLabel(), audio?.selectedFader()?.let{audio.displayName(it)})
}
internal fun sonarSceneRequest(controller:SonarNativeController,night:Boolean,audio:AudioNativeController?,unit:DepthUnit=DepthUnit.METERS,dashboardPreview:Boolean=false):SonarSceneRequest {
    val model=controller.model;val ring=model.viewRing;val bounds=controller.bounds()
    val frame=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,controller.gpuSourceContext(),model.viewDisplay,
        model.viewMetersPerBin,model.viewAmplitudeFullScale,bounds.start,bounds.end,controller.linearInterpolation,night,1f,true,controller.displayRevision,
        if(model.viewDisplay.overlayTargets)model.viewPresentation?.tracks?.take(64)?.map{it.bin.toFloat()}?:emptyList()else emptyList(),dashboardPreview,bounds.state)
    return SonarSceneRequest(frame,sonarSceneUi(controller,unit,audio))
}

@Composable
internal fun SonarGpuScenePlot(controller:SonarNativeController,night:Boolean,depthUnit:DepthUnit,audio:AudioNativeController?,modifier:Modifier,dashboardPreview:Boolean=false){
    val language=com.no5.ui.LocalNo5Language.current
    // AndroidView's factory captures its Controller. A new source-session
    // Controller must own a new scene, not update a View of its disposed peer.
    key(controller,controller.gpuRebuildRevision){
        var scene by remember { mutableStateOf<SonarGpuSceneView?>(null) }
        AndroidView(modifier=modifier,factory={context->SonarGpuSceneView(context,controller).also{
            controller.attachGpuView("SONAR",it.gpu);scene=it
        }},onRelease={controller.detachGpuView("SONAR",it.gpu);if(scene===it)scene=null;it.close()})
        LaunchedEffect(scene,controller,night,depthUnit,audio,language,dashboardPreview){
            val target=scene?:return@LaunchedEffect
            snapshotFlow { sonarSceneRequest(controller,night,audio,depthUnit,dashboardPreview) }.collect { target.update(it.frame,depthUnit,audio,it.ui) }
        }
    }
}
