package com.no5.helm.engine
import com.no5.helm.chart.ChartFrame
import java.util.concurrent.atomic.*
internal class TouchFrameBuffer(val stamp:Long,val cameraRevision:Long,val generation:Long) {
    @Volatile var frame:ChartFrame?=null
    val swapped=AtomicBoolean()
}
/** Single producer/consumer slot; invalidation never cancels native work already running. */
internal class TouchFrameGate {
    val held=AtomicBoolean()
    val generation=AtomicLong()
    private val pending=AtomicReference<TouchFrameBuffer?>()
    fun invalidate(){generation.incrementAndGet();held.set(false);pending.set(null)}
    fun begin(revision:Long,stamp:Long):TouchFrameBuffer? {
        if(held.get())return null
        val b=TouchFrameBuffer(stamp,revision,generation.get());if(!pending.compareAndSet(null,b))return null
        if(held.get()||b.generation!=generation.get()){pending.compareAndSet(b,null);return null};return b
    }
    fun current()=pending.get()
    fun swapped(b:TouchFrameBuffer,frame:ChartFrame?){b.frame=frame;b.swapped.set(true)}
    fun consume(stamp:Long):TouchFrameBuffer? {
        val b=pending.get()?.takeIf{it.stamp==stamp&&it.swapped.get()}?:return null
        return if(pending.compareAndSet(b,null))b else null
    }
    fun valid(b:TouchFrameBuffer,revision:Long)=!held.get()&&b.generation==generation.get()&&b.cameraRevision==revision
    fun discard(b:TouchFrameBuffer)=pending.compareAndSet(b,null)
}
