package com.no5.helm

import org.junit.Assert.*
import org.junit.Test

/** Pure checks of the same geometry used by the native fascia and Compose hit regions. */
class DashboardInstrumentGeometryTest {
    @Test fun everyActionHasAtLeastFortyFourDpOnLuminaAndAvoidsHeaderFooterAndKnobs() {
        val rectangles=DashboardGeometry.actions
        for((name,rect)in rectangles) {
            assertTrue("$name touch width",rect.width*.5f>=44f)
            assertTrue("$name touch height",rect.height*.5f>=44f)
            assertTrue("$name under header/version",rect.y>=144f)
            assertTrue("$name over footer",rect.bottom<NativeHelmLayout.FOOTER_TOP)
            assertTrue("$name physical knob clearance",KnobGeometry.safe(rect.x,rect.y,rect.right,rect.bottom))
        }
        for((index,a)in rectangles.entries.withIndex())for(b in rectangles.entries.drop(index+1))
            assertFalse("${a.key} overlaps ${b.key}",a.value.overlaps(b.value))
    }
    @Test fun detailNavigationCannotInterceptTheSharedAudioAndSonarControls() {
        val details=listOf(DashboardGeometry.helm,DashboardGeometry.audioDetail,DashboardGeometry.sonarDetail)
        for(detail in details) {
            assertTrue(KnobGeometry.safe(detail.x,detail.y,detail.right,detail.bottom))
            for((name,action)in DashboardGeometry.actions)assertFalse("Detail region intercepts $name",detail.overlaps(action))
        }
        val echo=DashboardGeometry.echogram
        assertTrue(echo.x>=DashboardGeometry.sonarDetail.x&&echo.right<=DashboardGeometry.sonarDetail.right)
        assertTrue(echo.y>=DashboardGeometry.sonarDetail.y&&echo.bottom<=DashboardGeometry.sonarDetail.bottom)
    }
    @Test fun audioStripAndSonarActionsStayInTheirOwnContinuousWorkingRegions() {
        for((name,rect)in DashboardGeometry.actions) {
            val area=if(name.startsWith("dashboard-range")||name.startsWith("dashboard-gain")||name=="dashboard-freeze")DashboardGeometry.sonar else DashboardGeometry.audio
            assertTrue("$name leaves its region",rect.x>=area.x&&rect.y>=area.y&&rect.right<=area.right&&rect.bottom<=area.bottom)
        }
    }
}
