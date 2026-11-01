package com.no5.helm.gpu

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.no5.helm.SonarNativeController

/** Same source/ring for both layouts; TextureView receives its actual measured pixels. */
@Composable
internal fun SonarGpuPlot(controller: SonarNativeController, night: Boolean, tag: String, modifier: Modifier) {
    controller.snapshot;controller.linearInterpolation
    val model=controller.model
    val ring=model.viewRing
    val bounds=controller.bounds()
    val context=controller.gpuSourceContext()
    var actualWidth by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val raster=if(tag=="SONAR"&&actualWidth>0)actualWidth/2000f else 1f
    val frame=SonarGpuFrame(ring,ring.lifecycleGeneration,ring.version,context,model.viewDisplay,
        model.viewMetersPerBin,model.viewAmplitudeFullScale,bounds.start,bounds.end,controller.linearInterpolation,night,raster,tag=="SONAR",controller.displayRevision,
        viewportState=bounds.state)
    androidx.compose.runtime.key(controller,tag,controller.gpuRebuildRevision) {
    AndroidView(modifier=modifier.then(Modifier.onSizeChanged { actualWidth=it.width }),
        factory={sourceContext->SonarGpuView(sourceContext).also { view->
            controller.attachGpuView(tag,view)
            view.onTexturePresented={controller.gpuPresented(it)}
        }},
        update={it.submit(frame)},
        onRelease={controller.detachGpuView(tag,it);it.close()})
    }
}
