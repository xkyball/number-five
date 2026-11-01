package com.no5.helm

import org.junit.Assert.*
import org.junit.Test

class AudioOverlayIdleTest {
    @Test fun passiveUpdatesDoNotExtendTenSecondDeadline() {
        var clock=0L; val idle=AudioOverlayIdle({clock})
        clock=9999;assertFalse(idle.expired());clock=10000;assertTrue(idle.expired())
        idle.activity();clock=19999;assertFalse(idle.expired());clock=20000;assertTrue(idle.expired())
    }
    @Test fun gestureMomentumPendingAndAccessibilityHoldDeadlineUntilCompletion() {
        for(mode in 0..3) {
            var clock=0L;val idle=AudioOverlayIdle({clock})
            when(mode){0->idle.pointer=true;1->idle.scrolling=true;2->idle.pending=true;else->idle.touchExploration=true}
            clock=30000;assertFalse(idle.expired())
            idle.pointer=false;idle.scrolling=false;idle.pending=false;idle.touchExploration=false
            clock=39999;assertFalse(idle.expired());clock=40000;assertTrue(idle.expired())
        }
    }
}
