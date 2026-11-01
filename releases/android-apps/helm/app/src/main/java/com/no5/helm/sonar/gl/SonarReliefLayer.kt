package com.no5.helm.sonar.gl

import com.no5.domain.SonarImageMode

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.os.PowerManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.no5.helm.SonarLayout
import com.no5.helm.SonarNativeController
import kotlin.math.pow
import com.no5.domain.DepthUnit
import com.no5.ui.No5Text
import java.util.Locale

/** No telemetry client or acquisition action; this layer consumes the same retained source ring. */
@Composable
internal fun SonarReliefLayer(scale:Float,night:Boolean,controller:SonarNativeController,depthUnit:DepthUnit) {
    val context=LocalContext.current
    val hardware=LocalView.current.isHardwareAccelerated
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val power=remember(context){context.getSystemService(PowerManager::class.java)}
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    var thermal by remember { mutableIntStateOf(power.currentThermalStatus) }
    var memoryPressure by remember { mutableStateOf(false) }
    DisposableEffect(lifecycle,context) {
        val observer=LifecycleEventObserver{_,_->resumed=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        val heat=PowerManager.OnThermalStatusChangedListener{thermal=it}
        val memory=object:ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig:Configuration){}
            override fun onLowMemory(){memoryPressure=true}
            override fun onTrimMemory(level:Int){if(level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level==ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level>=ComponentCallbacks2.TRIM_MEMORY_MODERATE)memoryPressure=true}
        }
        lifecycle.addObserver(observer);power.addThermalStatusListener(context.mainExecutor,heat);context.registerComponentCallbacks(memory)
        onDispose{lifecycle.removeObserver(observer);power.removeThermalStatusListener(heat);context.unregisterComponentCallbacks(memory);controller.reliefReady=false}
    }
    val s=controller.snapshot
    val geometrySupported=controller.model.viewHistoryHasUniformGeometry()&&!controller.model.imageHistoryAggregated
    val eligible=controller.bounds().available && !controller.model.viewDisplay.exactPingProfiles && controller.reliefEnabled && controller.reliefFailure==null && s.ringCount>1 && resumed && hardware && geometrySupported && !memoryPressure && thermal<PowerManager.THERMAL_STATUS_SEVERE
    if(!eligible){
        LaunchedEffect(eligible){controller.reliefReady=false}
        return
    }
    val feed=remember(controller){SonarReliefFeed()}
    var view by remember { mutableStateOf<SonarReliefTextureView?>(null) }
    val width=SonarLayout.plotWidth(s.display.aScope)
    AndroidView(modifier=Modifier.offset((SonarLayout.PLOT_LEFT*scale).dp,(SonarLayout.TOP*scale).dp).size((width*scale).dp,(SonarLayout.HEIGHT*scale).dp),
        factory={SonarReliefTextureView(it,{ready->controller.reliefReady=ready},{failure->controller.reliefFailure=failure;controller.tell(failure)}).also{view=it;controller.registerReliefView(it)}},
        onRelease={controller.unregisterReliefView(it);it.dispose();view=null},update={})
    LaunchedEffect(view,controller,night) {
        snapshotFlow {
            val snapshot=controller.snapshot
            val d=controller.model.viewDisplay;val b=controller.bounds()
            ReliefFrame(feed.snapshot(controller.model.viewRing,d.historySeconds),b.start.toFloat(),b.end.toFloat(),
                10.0.pow(d.gainDb/20).toFloat(),d.gamma.toFloat(),d.threshold.toFloat(),d.palette.ordinal,night,
                controller.reliefYaw,controller.reliefPitch,controller.reliefZoom,controller.reliefAligned,
                (if(d.gainMode==SonarImageMode.AUTO)1 else 0) or (if(d.gammaMode==SonarImageMode.AUTO)2 else 0) or (if(d.thresholdMode==SonarImageMode.AUTO)4 else 0),10.0.pow(d.autoGainOffsetDb/20).toFloat())
        }.collect{view?.submit(it)}
    }
}
