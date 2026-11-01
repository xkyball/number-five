package com.no5.helm

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** UI-thread bitmap ownership. No worker wait and no acquisition/network side effect. */
@Composable
internal fun SonarRendererLifecycle(renderer:SonarNativeRenderer) {
    val context=LocalContext.current.applicationContext
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(renderer,context,lifecycle) {
        val memory=object:ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig:Configuration)=Unit
            override fun onLowMemory(){renderer.releaseTemporalResources(false)}
            override fun onTrimMemory(level:Int){
                if(level==ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)renderer.releaseTemporalResources()
                else if(level>=ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW)renderer.releaseTemporalResources(false)
            }
        }
        val observer=LifecycleEventObserver{_,event->if(event==Lifecycle.Event.ON_STOP)renderer.releaseTemporalResources()}
        context.registerComponentCallbacks(memory);lifecycle.addObserver(observer)
        onDispose{lifecycle.removeObserver(observer);context.unregisterComponentCallbacks(memory);renderer.dispose()}
    }
}
