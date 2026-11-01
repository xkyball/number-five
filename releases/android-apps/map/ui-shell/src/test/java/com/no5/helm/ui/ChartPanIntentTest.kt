package com.no5.helm.ui

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class ChartPanIntentTest {
    @Test fun pureZoomAndRotationNeverBecomePanButALaterDeliberateTranslationDoes(){
        for(policy in ChartTouchPolicy.entries){
            val tracker=ChartTouchTracker(8.0,500,16.0,policy)
            val original=listOf(TouchPoint(1,-100.0,0.0),TouchPoint(2,100.0,0.0))
            tracker.handle(TouchAction.DOWN,listOf(original[0]),0)
            tracker.handle(TouchAction.POINTER_DOWN,original,1)
            fun pose(scale:Double,angle:Double,x:Double=0.0,y:Double=0.0)=original.map{p->TouchPoint(p.id,p.x*scale*cos(angle)+x,p.x*scale*sin(angle)+y)}
            assertFalse(policy.name,tracker.handle(TouchAction.MOVE,pose(1.5,0.0),2).panIntent)
            assertFalse(policy.name,tracker.handle(TouchAction.MOVE,pose(1.5,PI/3),3).panIntent)
            assertTrue(policy.name,tracker.handle(TouchAction.MOVE,pose(1.5,PI/3,30.0,20.0),4).panIntent)
            assertFalse("One pan notification per Android sequence",tracker.handle(TouchAction.MOVE,pose(1.5,PI/3,40.0,30.0),5).panIntent)
            tracker.handle(TouchAction.CANCEL,emptyList(),6)
            tracker.handle(TouchAction.DOWN,listOf(TouchPoint(3,0.0,0.0)),7)
            assertTrue(tracker.handle(TouchAction.MOVE,listOf(TouchPoint(3,20.0,0.0)),8).panIntent)
        }
    }
    @Test fun minorPinchCentroidNoiseDoesNotCancelFollowAndSlowRealPanCrossesTheSameTouchSlop(){
        val tracker=ChartTouchTracker(8.0,500)
        val original=listOf(TouchPoint(1,-100.0,0.0),TouchPoint(2,100.0,0.0))
        tracker.handle(TouchAction.DOWN,listOf(original[0]),0);tracker.handle(TouchAction.POINTER_DOWN,original,1)
        for(i in 1..7)assertFalse(tracker.handle(TouchAction.MOVE,listOf(TouchPoint(1,-100.0-i*3+i,0.0),TouchPoint(2,100.0+i*3+i,0.0)),2L+i).panIntent)
        assertTrue(tracker.handle(TouchAction.MOVE,listOf(TouchPoint(1,-116.0,0.0),TouchPoint(2,134.0,0.0)),20).panIntent)
    }
}
