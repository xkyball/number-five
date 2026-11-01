package com.no5.preparation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import kotlin.math.abs
import kotlin.math.hypot

/** Only a single-pointer DOWN in the accessible bottom band can become a dock swipe. */
internal class BottomDockSwipe(private val slop:Float,private val openDistance:Float) {
    private enum class Phase { IDLE,CANDIDATE,CLAIMED,REJECTED }
    private var phase=Phase.IDLE
    private var origin=Offset.Zero
    private var claimed=false
    fun down(point:Offset,band:Rect?) { cancel();origin=point;if(band?.contains(point)==true)phase=Phase.CANDIDATE }
    /** True exactly once: the map stream must be cancelled before this MOVE reaches it. */
    fun move(point:Offset):Boolean {
        val dx=point.x-origin.x;val up=origin.y-point.y
        if(phase==Phase.CANDIDATE && hypot(dx,up)>slop) {
            if(up>slop && up>=abs(dx)*1.5f) { phase=Phase.CLAIMED;claimed=true;return true }
            phase=Phase.REJECTED
        }
        return false
    }
    fun reject() { phase=Phase.REJECTED }
    fun up(point:Offset):Boolean {
        val up=origin.y-point.y;val dx=abs(point.x-origin.x)
        val open=phase==Phase.CLAIMED && claimed && up>=openDistance && up>=dx*1.5f
        cancel();return open
    }
    fun cancel() { phase=Phase.IDLE;claimed=false }
}
