package com.no5.helm.video

import com.no5.helm.ocean.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VideoPolicyTest {
    @Test fun cardinalDirectionsAndSeamUseSphericalProjection(){
        assertEquals(.5,VideoPolicy.uv(.5,.28,0.0).first,1e-8)
        assertEquals(.75,VideoPolicy.uv(.5,.28,PI/2).first,1e-8)
        assertEquals(0.0,VideoPolicy.uv(.5,.28,PI).first,1e-8)
        assertEquals(.25,VideoPolicy.uv(.5,.28,3*PI/2).first,1e-8)
        assertEquals(.5,VideoPolicy.uv(.5,.28,0.0).second,1e-8)
        assertTrue(VideoPolicy.uv(.5,0.0,0.0).second<.5)
        assertTrue(VideoPolicy.uv(.5,1.0,0.0).second>.5)
    }
    @Test fun headingInterpolationCrossesNorthByShortRouteAndStaleFreezes(){
        val mapper=OceanPresentation("v")
        mapper.accept(OceanInputs(sessionId="v",headingDegrees=359.0,headingQuality=OceanInputQuality.LIVE));mapper.setAnimationActive(true)
        mapper.accept(OceanInputs(sessionId="v",headingDegrees=1.0,headingQuality=OceanInputQuality.LIVE))
        val frame=mapper.frame(0f,.05f,OceanQuality.HIGH)
        val deg=Math.toDegrees(frame.headingRadians.toDouble())
        assertTrue(abs(OceanPresentation.shortestDelta(359.0,deg))<2)
        mapper.accept(OceanInputs(sessionId="v",headingDegrees=180.0,headingQuality=OceanInputQuality.STALE))
        assertEquals(frame.headingRadians,mapper.frame(0f,.05f,OceanQuality.HIGH).headingRadians,1e-6f)
        val north359=VideoPolicy.uv(.5,.28,Math.toRadians(359.0)).first
        val north1=VideoPolicy.uv(.5,.28,Math.toRadians(1.0)).first
        assertEquals(2.0/360,north1-north359,1e-8)
    }
    @Test fun staleHeadingSurvivesPagePauseAndSourceSessionChangeResetsIt(){
        val state=VideoState();val before=state.mapperFor("boat")
        before.accept(OceanInputs(sessionId="boat",headingDegrees=275.0,headingQuality=OceanInputQuality.LIVE));before.setAnimationActive(true)
        val heading=before.frame(0f,0f,OceanQuality.HIGH).headingRadians
        before.setAnimationActive(false)
        val resumed=state.mapperFor("boat")
        resumed.accept(OceanInputs(sessionId="boat",headingDegrees=100.0,headingQuality=OceanInputQuality.STALE));resumed.setAnimationActive(true)
        assertEquals(heading,resumed.frame(0f,.05f,OceanQuality.HIGH).headingRadians,1e-6f)
        val newSession=state.mapperFor("different boat")
        assertEquals(0f,newSession.frame(0f,0f,OceanQuality.HIGH).headingRadians,0f)
    }
    @Test fun loopingAndResumeResetWallClockWithoutWaitingForOldTimestamp(){
        val clock=VideoTimeline()
        assertTrue(clock.due(80_000_000,10_000))
        assertFalse(clock.due(80_040_000,39_000_000))
        assertTrue(clock.due(80_040_000,40_010_000))
        clock.reset() // EOS loop or a new decoder on lifecycle resume.
        assertTrue(clock.due(0,90_000_000_000))
        assertFalse(clock.due(40_000,90_010_000_000))
    }
    @Test fun lifecyclePageNightReducedMotionFailureAndMissingFileGateDecoder(){
        val good=booleanArrayOf(true,true,false,false,true,true,true,false)
        fun active(a:BooleanArray)=VideoPolicy.visible(a[0],a[1],a[2],a[3],a[4],a[5],a[6],a[7])
        assertTrue(active(good))
        for(i in good.indices){val changed=good.copyOf();changed[i]=!changed[i];assertFalse("gate $i",active(changed))}
        assertTrue(active(good)) // Resume makes it eligible again without altering URI/position.
    }
    @Test fun metadataAndFogRejectWrongProjectionOrUntrustedWeather(){
        assertTrue(VideoPolicy.validPanorama(4096,2048,0));assertFalse(VideoPolicy.validPanorama(1920,1080,0));assertFalse(VideoPolicy.validPanorama(2048,1024,90))
        assertEquals(0f,VideoPolicy.fog(50.0,false));assertEquals(0f,VideoPolicy.fog(Double.NaN,true))
        assertEquals(.55f,VideoPolicy.fog(50.0,true));assertEquals(0f,VideoPolicy.fog(20000.0,true))
    }
    @Test fun videoSelectionUsesExistingSaveCancelTransaction(){
        val selection=BackgroundSelection(BackgroundMode.IMAGE,"content://old")
        selection.selectVideo("https://not-a-local-document");assertFalse(selection.dirty)
        selection.selectVideo("content://new");assertTrue(selection.dirty);assertEquals("content://old",selection.activeVideoUri)
        selection.cancel();assertEquals("content://old",selection.draftVideoUri);assertEquals(BackgroundMode.IMAGE,selection.draft)
        selection.selectVideo("content://new");val request=selection.beginSave()!!
        selection.selectVideo("content://late");selection.finishSave(request,false)
        assertEquals("content://old",selection.activeVideoUri)
        val retry=selection.beginSave()!!;selection.finishSave(retry,true)
        assertEquals("content://new",selection.activeVideoUri);assertEquals(BackgroundMode.VIDEO,selection.active)
    }
}
