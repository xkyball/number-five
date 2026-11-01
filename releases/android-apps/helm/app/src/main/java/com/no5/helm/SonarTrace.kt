package com.no5.helm

import android.os.Trace

/** Perfetto/atrace markers only. These local timings never replace physical-origin freshness. */
internal object SonarTrace {
    inline fun <T> phase(name:String, block:()->T):T {
        if(android.os.Build.VERSION.SDK_INT<29 || !Trace.isEnabled())return block()
        Trace.beginSection(name)
        try{return block()}finally{Trace.endSection()}
    }
    fun section(name:String,begin:Boolean){
        if(android.os.Build.VERSION.SDK_INT<29)return
        if(begin)Trace.beginSection(name)else Trace.endSection()
    }
    fun ping(name:String,id:Long){if(android.os.Build.VERSION.SDK_INT>=29 && Trace.isEnabled())Trace.setCounter(name,id)}
    fun localPresentation(received:Long,ping:Long){
        ping("sonar.window.ping",ping)
        if(received>0 && android.os.Build.VERSION.SDK_INT>=29 && Trace.isEnabled()){val elapsed=System.nanoTime()-received;if(elapsed>=0)Trace.setCounter("sonar.local_to_window_us",(elapsed+999)/1000)}
    }
}
