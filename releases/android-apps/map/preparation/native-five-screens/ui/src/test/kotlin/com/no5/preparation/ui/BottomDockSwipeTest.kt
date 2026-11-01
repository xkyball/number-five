package com.no5.preparation.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.*
import org.junit.Test

class BottomDockSwipeTest {
    private val edge=Rect(350f,1176f,1656f,1200f)
    private val start=Offset(1000f,1198f)
    @Test fun opensOnlyAfterACompleteUpwardSwipeStartingInTheEdgeBand() {
        val swipe=BottomDockSwipe(16f,128f)
        swipe.down(start,edge)
        assertFalse(swipe.move(start-Offset(0f,10f)))
        assertTrue(swipe.move(start-Offset(5f,40f)))
        assertFalse(swipe.move(start-Offset(10f,160f)))
        assertTrue(swipe.up(start-Offset(10f,160f)))
        assertFalse(swipe.up(start-Offset(10f,160f)))
        for(p in listOf(Offset(1000f,1170f),Offset(50f,1198f),Offset(1940f,1198f))) {
            swipe.down(p,edge);assertFalse(swipe.move(p-Offset(0f,200f)));assertFalse(swipe.up(p-Offset(0f,200f)))
        }
    }
    @Test fun shortOrMostlyDiagonalMovementDoesNotOpenTheDock() {
        val swipe=BottomDockSwipe(16f,128f)
        swipe.down(start,edge);assertTrue(swipe.move(start-Offset(0f,40f)));assertFalse(swipe.up(start-Offset(0f,70f)))
        swipe.down(start,edge);assertFalse(swipe.move(start+Offset(100f,-100f)));assertFalse(swipe.move(start+Offset(100f,-300f)))
        assertFalse(swipe.up(start+Offset(100f,-300f)))
    }
    @Test fun pointerChangesAndCancelRejectTheRestOfTheSequenceAndNextDownRecovers() {
        val swipe=BottomDockSwipe(16f,128f)
        for(claimFirst in listOf(false,true)) {
            swipe.down(start,edge);if(claimFirst)assertTrue(swipe.move(start-Offset(0f,40f)))
            swipe.reject();assertFalse(swipe.move(start-Offset(0f,200f)));assertFalse(swipe.up(start-Offset(0f,200f)))
        }
        swipe.down(start,edge);assertTrue(swipe.move(start-Offset(0f,40f)));swipe.cancel()
        assertFalse(swipe.up(start-Offset(0f,200f)))
        swipe.down(start,edge);assertTrue(swipe.move(start-Offset(0f,200f)));assertTrue(swipe.up(start-Offset(0f,200f)))
    }
    @Test fun aDisabledBandCannotClaimEvenALongSwipe() {
        val swipe=BottomDockSwipe(16f,128f)
        swipe.down(start,null);assertFalse(swipe.move(start-Offset(0f,300f)));assertFalse(swipe.up(start-Offset(0f,300f)))
    }
}
