package com.no5.helm

import com.no5.domain.*
import org.junit.Test
import kotlin.test.*

class SoundMeasuredGeometryTest {
    @Test fun unmeasuredBootHasNeitherDimensionsNorInventedListenerOrSub() {
        val empty=SoundCalibration()
        val draft=SoundMeasurementDraft.from(empty)
        assertEquals("",draft.width);assertEquals("",draft.length);assertFalse(draft.dirty)
        assertNull(draft.dimensions());assertNull(SoundMeasuredGeometry.bounds(empty))
        val main=SoundMeasuredGeometry.rectangle(empty,1.6,4.1)
        assertTrue(SoundMeasuredGeometry.rectangular(main));assertNull(main.listenerM)
        assertNull(main.speakers.single{it.id==SoundSpeakerId.SUB}.positionM)
        assertFalse(main.geometryEnabled);assertFalse(main.completeGeometry)
    }
    @Test fun onlyExplicitRectangleDerivationChangesMainPositionsAndPreservesOtherOwnedData() {
        val input=SoundCalibration(listOf(
            SoundSpeaker(SoundSpeakerId.FRONT_LEFT,SoundPosition(-.8,3.0,.3),.2,-1.0),
            SoundSpeaker(SoundSpeakerId.FRONT_RIGHT,SoundPosition(.8,3.2,.4)),
            SoundSpeaker(SoundSpeakerId.REAR_LEFT,SoundPosition(-.6,7.0,.6)),
            SoundSpeaker(SoundSpeakerId.REAR_RIGHT,SoundPosition(.6,7.0,.5)),
            SoundSpeaker(SoundSpeakerId.SUB,SoundPosition(.15,5.4,.2),2.0,-3.0)),SoundPosition(.1,5.8,1.2),true)
        assertFalse(SoundMeasuredGeometry.rectangular(input))
        SoundMeasurementDraft.from(input)
        assertEquals(.3,input.speakers.first().positionM!!.z)
        val result=SoundMeasuredGeometry.rectangle(input,1.7,4.2)
        assertEquals(input.listenerM,result.listenerM)
        assertEquals(input.speakers.last(),result.speakers.last())
        assertEquals(.2,result.speakers.first().manualDelayMs);assertEquals(-1.0,result.speakers.first().manualGainDb)
        assertEquals(SoundPosition(-.8,3.0),result.speakers.first().positionM)
        assertEquals(.9,result.speakers[1].positionM!!.x,1e-9)
        assertEquals(7.2,result.speakers[2].positionM!!.y,1e-9)
        assertTrue(SoundMeasuredGeometry.rectangular(result));assertTrue(result.geometryEnabled)
    }
    @Test fun localFieldsAcceptCommaButRejectPartialNonfiniteOrOutOfRangeValues() {
        assertEquals(1.7 to 4.6,SoundMeasurementDraft("1,7","4,6").dimensions())
        for(value in listOf("","-1","0","NaN","Infinity","51","0.01"))assertNull(SoundMeasurementDraft(value,"4").dimensions(),value)
        assertEquals(.05 to 50.0,SoundMeasurementDraft("0.05","50").dimensions())
        assertTrue(SoundMeasurementDraft("1","2").copy(width="1.5").dirty)
    }
    @Test fun rectangularApplyDoesNotQuietlyShiftAnExistingCoordinateReferenceAtWireBoundary() {
        val points=listOf(SoundPosition(49.0,0.0),SoundPosition(50.0,0.0),SoundPosition(49.0,1.0),SoundPosition(50.0,1.0))
        val input=SoundCalibration(SoundSpeakerId.entries.mapIndexed{i,id->SoundSpeaker(id,points.getOrNull(i))})
        assertFailsWith<IllegalArgumentException>{SoundMeasuredGeometry.rectangle(input,2.0,2.0)}
        assertEquals(49.0,input.speakers.first().positionM!!.x)
    }
}
