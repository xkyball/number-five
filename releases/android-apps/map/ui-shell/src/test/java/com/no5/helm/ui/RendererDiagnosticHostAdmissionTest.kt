package com.no5.helm.ui

import com.no5.helm.model.MapPositionState
import com.no5.helm.model.PositionIntent
import com.no5.helm.model.PositionMode
import org.junit.Assert.*
import org.junit.Test

class RendererDiagnosticHostAdmissionTest {
    @Test fun idleOffIsOnlyHostAdmissionNotAnEngineReadinessClaim() {
        assertNull(rendererDiagnosticCameraStartBlocker(MapPositionState(), false))
    }
    @Test fun centeredDoesNotRepeatOrChangeTheOneShotIntent() {
        val state = MapPositionState(mode = PositionMode.CENTERED)
        assertNull(rendererDiagnosticCameraStartBlocker(state, false))
        assertEquals(PositionMode.CENTERED, state.mode)
        assertFalse(state.intent.follow)
    }
    @Test fun pausedFollowIntentBlocksWithoutClearingIt() {
        val state = MapPositionState(intent = PositionIntent(follow = true), followActive = false,
            awaitingFreshFix = true)
        assertNotNull(rendererDiagnosticCameraStartBlocker(state, false))
        assertTrue(state.intent.follow)
        assertTrue(state.awaitingFreshFix)
    }
    @Test fun activeFollowOrExplicitFollowModeBlocks() {
        assertNotNull(rendererDiagnosticCameraStartBlocker(MapPositionState(followActive = true), false))
        assertNotNull(rendererDiagnosticCameraStartBlocker(MapPositionState(mode = PositionMode.FOLLOW), false))
    }
    @Test fun touchAndOtherHostWorkBlockIndependently() {
        assertNotNull(rendererDiagnosticCameraStartBlocker(MapPositionState(touchActive = true), false))
        assertNotNull(rendererDiagnosticCameraStartBlocker(MapPositionState(), true))
    }
}
