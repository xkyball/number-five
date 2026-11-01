package com.no5.helm.video

import kotlin.math.*

/** Playback uses monotonic wall time, never source UTC or simulator timeScale. */
internal class VideoTimeline {
    private var anchorNanos: Long? = null
    private var anchorPtsUs=0L
    fun reset() { anchorNanos=null }
    fun due(ptsUs: Long, nowNanos: Long): Boolean {
        if(anchorNanos==null) { anchorNanos=nowNanos; anchorPtsUs=ptsUs }
        return nowNanos-anchorNanos!! >= (ptsUs-anchorPtsUs).coerceAtLeast(0)*1000
    }
}
internal object VideoPolicy {
    fun visible(helm: Boolean,resumed: Boolean,night: Boolean,reduced: Boolean,hardware: Boolean,selected: Boolean,hasFile: Boolean,failed: Boolean)=
        helm && resumed && !night && !reduced && hardware && selected && hasFile && !failed
    fun validPanorama(width: Int,height: Int,rotation: Int)= width>0 && height>0 && abs(width-2L*height)<=2 && rotation%360==0
    fun fog(visibility: Double?,live: Boolean): Float = if(!live || visibility==null || !visibility.isFinite() || visibility<0) 0f else
        ((3000-visibility)/3000).coerceIn(0.0,.55).toFloat()
    /** Oracle for the fragment shader. x/y are top-left-origin normalized screen coordinates. */
    fun uv(x: Double,y: Double,heading: Double,aspect: Double=1200.0/704): Pair<Double,Double> {
        val rx=(x-.5)*2*tan(.675);val ry=(.28-y)*2*tan(.675)/aspect
        val xx=rx*cos(heading)+sin(heading);val zz=cos(heading)-rx*sin(heading)
        val len=sqrt(rx*rx+ry*ry+1)
        return (((atan2(xx,zz)/(2*PI)+.5)%1+1)%1) to (.5-asin(ry/len)/PI)
    }
}
