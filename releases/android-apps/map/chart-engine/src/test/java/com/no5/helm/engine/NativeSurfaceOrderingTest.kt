package com.no5.helm.engine

import com.no5.helm.chart.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicBoolean

/** Actual engine worker transactions and status; only EGL attachment/detachment is represented by a loan. */
class NativeSurfaceOrderingTest {
    private lateinit var engine:NativeChartEngine
    private var nativeOwner:Long?=null
    private val released=mutableSetOf<Long>()
    private var nativeDetachCalls=0
    private val first=ChartSurfaceGeometry.fit(2000,1070)
    private val replacement=ChartSurfaceGeometry.fit(1980,1050)
    private val home=ChartViewport(-33.9,18.4,.01)
    private val firstSurface=Any()
    private val secondSurface=Any()
    private val firstAttachment=Any()
    private val firstOwner=NativeSurfaceOwner(firstSurface,secondSurface,firstAttachment)
    private fun field(name:String)=NativeChartEngine::class.java.getDeclaredField(name).apply{isAccessible=true}
    @Suppress("UNCHECKED_CAST") private fun state()=field("mutable").get(engine) as MutableStateFlow<ChartEngineStatus>
    private fun generation()=engine.status.value.surfaceGeneration
    private fun geometry()=field("surfaceGeometry").get(engine)
    private fun pending()=(field("surfaceCommandPending").get(engine) as AtomicBoolean).get()
    private fun admitted(command:Long,owner:NativeSurfaceOwner=NativeSurfaceOwner(Any())) {
        assertEquals(command,engine.prepareSurfaceAttachment(owner,false))
    }
    private fun attach(command:Long,geometry:ChartSurfaceGeometry,owner:NativeSurfaceOwner?=null)=engine.applySurfaceAttachment(command,geometry,null,{
        nativeOwner?.let{released+=it} // The real native attach first releases the previous EGL loan.
        nativeOwner=command
    },{true},owner)
    private fun nativeDetach(){nativeDetachCalls++;nativeOwner?.let{released+=it};nativeOwner=null}
    @Before fun setup() {
        engine=NativeChartEngine(CameraControlClock{100L},NativeCameraApplication{_,view->view})
        state().value=ChartEngineStatus(initialized=true,chartLoaded=true,chartSourceGeneration=11,viewport=home)
        admitted(1,firstOwner);assertTrue(attach(1,first,firstOwner));assertTrue(engine.status.value.surfaceAttached)
    }
    @After fun close(){engine.close()}

    @Test fun lateOldDetachPreservesActuallyAppliedReplacementAndReleasesOnlyOldLoan() {
        assertEquals(2L,engine.prepareSurfaceDetachment()) // Old detach pauses before reaching the worker queue.
        admitted(3);assertTrue(attach(3,replacement))
        val attachedGeneration=generation();val before=engine.status.value
        var callbacks=0
        val detached=engine.applySurfaceDetachment(2,{
            callbacks++
            assertTrue("The replacement attach must have released the old EGL loan first",1L in released)
            assertEquals(3L,nativeOwner)
        },::nativeDetach)
        assertFalse(detached);assertEquals(0,nativeDetachCalls);assertEquals(1,callbacks)
        assertEquals(3L,nativeOwner);assertEquals(attachedGeneration,generation())
        assertSame(replacement,geometry());assertEquals(before,engine.status.value)
        assertTrue(engine.status.value.surfaceAttached);assertFalse(pending())
        assertTrue(engine.surfaceOwnerDiagnostics().contains("action=SKIP_DETACH command=2 appliedAttach=3"))
    }
    @Test fun detachBeforeQueuedReplacementWaitsForRealOldLoanReleaseAndLatestAttachReturns() {
        assertEquals(2L,engine.prepareSurfaceDetachment());admitted(3) // Replacement is announced but has not executed its native attach.
        val oldGeneration=generation();var callbacks=0
        val detached=engine.applySurfaceDetachment(2,{
            callbacks++
            assertNull("An announced attach must not allow early release of a still bound EGL loan",nativeOwner)
            assertTrue(1L in released)
            assertFalse(engine.status.value.surfaceAttached);assertNull(geometry())
        },::nativeDetach)
        assertTrue(detached);assertEquals(1,nativeDetachCalls);assertEquals(1,callbacks)
        assertTrue(pending());assertTrue(generation()>oldGeneration)
        val detachedGeneration=generation()
        assertTrue(attach(3,replacement))
        assertEquals(3L,nativeOwner);assertSame(replacement,geometry())
        assertTrue(engine.status.value.surfaceAttached);assertFalse(pending())
        assertTrue(generation()>detachedGeneration)
        assertEquals(11L,engine.status.value.chartSourceGeneration);assertEquals(home,engine.status.value.viewport)
        assertTrue(engine.surfaceOwnerDiagnostics().contains("action=DETACH command=2 appliedAttach=0"))
        assertTrue(engine.surfaceOwnerDiagnostics().contains("action=ATTACH command=3 appliedAttach=3"))
    }
    @Test fun obsoletePairMemberReleaseAfterReplacementDoesNotInvalidateOrDetachCurrentImageOwner() {
        val replacementOwner=NativeSurfaceOwner(Any(),Any())
        admitted(2,replacementOwner);assertTrue(attach(2,replacement,replacementOwner))
        val before=engine.status.value
        val cameraRevision=field("requestedCameraRevision").getLong(engine)
        assertEquals(true,field("touchTimestampSupported").get(engine))
        assertNull("Stale retirement must not allocate a newer command",engine.prepareOwnedSurfaceDetachment(secondSurface))
        assertEquals(before,engine.status.value)
        assertEquals(cameraRevision,field("requestedCameraRevision").getLong(engine))
        assertEquals(true,field("touchTimestampSupported").get(engine));assertFalse(pending())
        var callbacks=0
        assertFalse(engine.applySurfaceDetachment(2,{
            callbacks++;assertTrue(1L in released);assertEquals(2L,nativeOwner)
        },::nativeDetach,expectedSurface=secondSurface,ownsPendingCommand=false))
        assertEquals(1,callbacks);assertEquals(0,nativeDetachCalls)
        assertEquals(before,engine.status.value);assertSame(replacement,geometry())
        assertTrue(engine.surfaceOwnerDiagnostics().contains("action=SKIP_OWNER_RELEASE command=2 appliedAttach=2"))
    }
    @Test fun obsoleteMemberReleaseBeforePendingReplacementWaitsForActualOldLoanWithoutCancellingNewAttach() {
        val replacementOwner=NativeSurfaceOwner(Any(),Any())
        admitted(2,replacementOwner)
        val announced=engine.status.value
        val cameraRevision=field("requestedCameraRevision").getLong(engine)
        assertNull(engine.prepareOwnedSurfaceDetachment(firstSurface))
        assertEquals(announced,engine.status.value)
        assertEquals(cameraRevision,field("requestedCameraRevision").getLong(engine));assertTrue(pending())
        var callbacks=0
        assertTrue(engine.applySurfaceDetachment(2,{
            callbacks++;assertNull(nativeOwner);assertTrue(1L in released)
        },::nativeDetach,expectedSurface=firstSurface,ownsPendingCommand=false))
        assertEquals(1,callbacks);assertEquals(1,nativeDetachCalls)
        assertTrue("The announced replacement still owns its pending flag",pending())
        assertTrue("Old retirement must not make the replacement command stale",attach(2,replacement,replacementOwner))
        assertEquals(2L,nativeOwner);assertTrue(engine.status.value.surfaceAttached);assertSame(replacement,geometry());assertFalse(pending())
    }
    @Test fun reboundSameSurfaceRejectsFormerAttachmentTokenBeforeAdmissionAndAtActualWorkerOwner() {
        val replacementOwner=NativeSurfaceOwner(firstSurface,secondSurface,Any())
        admitted(2,replacementOwner);assertTrue(attach(2,replacement,replacementOwner))
        val before=engine.status.value
        val cameraRevision=field("requestedCameraRevision").getLong(engine)
        assertNull("Same Surface identity cannot authorize an old binding",engine.prepareOwnedSurfaceDetachment(secondSurface,firstAttachment))
        assertEquals(before,engine.status.value);assertEquals(cameraRevision,field("requestedCameraRevision").getLong(engine))
        assertEquals(true,field("touchTimestampSupported").get(engine));assertFalse(pending())
        var callbacks=0
        assertFalse(engine.applySurfaceDetachment(2,{
            callbacks++;assertTrue(1L in released);assertEquals(2L,nativeOwner)
        },::nativeDetach,expectedSurface=secondSurface,ownsPendingCommand=false,expectedAttachmentOwner=firstAttachment))
        assertEquals(1,callbacks);assertEquals(0,nativeDetachCalls);assertEquals(before,engine.status.value)
    }
    @Test fun formerTokenReleasesOldLoanBeforeRebindingSameSurfaceWithoutSupersedingAnnouncedOwner() {
        val replacementOwner=NativeSurfaceOwner(firstSurface,secondSurface,Any())
        admitted(2,replacementOwner)
        val before=engine.status.value
        val cameraRevision=field("requestedCameraRevision").getLong(engine)
        assertNull(engine.prepareOwnedSurfaceDetachment(firstSurface,firstAttachment))
        assertEquals(before,engine.status.value);assertEquals(cameraRevision,field("requestedCameraRevision").getLong(engine));assertTrue(pending())
        var callbacks=0
        assertTrue(engine.applySurfaceDetachment(2,{
            callbacks++;assertNull(nativeOwner);assertTrue(1L in released)
        },::nativeDetach,expectedSurface=firstSurface,ownsPendingCommand=false,expectedAttachmentOwner=firstAttachment))
        assertEquals(1,callbacks);assertEquals(1,nativeDetachCalls);assertTrue(pending())
        assertTrue("The announced binding still has its original valid command",attach(2,replacement,replacementOwner))
        assertEquals(2L,nativeOwner);assertSame(replacement,geometry());assertFalse(pending())
    }
    @Test fun visibleWakeUsesExistingRenderPumpOnceWithoutChangingCameraSourceOrSurfaceAuthority() {
        val before=engine.status.value
        val revisions=listOf("requestedCameraRevision","appliedCameraRevision","presentedCameraRevision").map{field(it).getLong(engine)}
        val request=assertNotNullRefresh(engine.prepareSurfaceRefresh(secondSurface,firstAttachment))
        assertTrue(engine.applySurfaceRefresh(request))
        assertFalse("An already delivered wake must not become a second action",engine.applySurfaceRefresh(request))
        assertTrue((field("renderDirty").get(engine) as AtomicBoolean).get())
        assertEquals(before,engine.status.value)
        assertEquals(revisions,listOf("requestedCameraRevision","appliedCameraRevision","presentedCameraRevision").map{field(it).getLong(engine)})
        assertEquals(0,nativeDetachCalls);assertEquals(1L,nativeOwner)
        assertEquals(1,engine.surfaceOwnerDiagnostics().lines().count{it.startsWith("surface action=REFRESH ")})
    }
    @Test fun obsoleteVisibleWakeIsRejectedBothBeforeAdmissionAndAfterReplacementReusesSameSurface() {
        val queued=assertNotNullRefresh(engine.prepareSurfaceRefresh(firstSurface,firstAttachment))
        val replacementOwner=NativeSurfaceOwner(firstSurface,secondSurface,Any())
        admitted(2,replacementOwner);assertTrue(attach(2,replacement,replacementOwner))
        val before=engine.status.value
        assertNull(engine.prepareSurfaceRefresh(firstSurface,firstAttachment))
        assertFalse(engine.applySurfaceRefresh(queued))
        assertEquals(before,engine.status.value);assertEquals(2L,nativeOwner)
        assertFalse((field("renderDirty").get(engine) as AtomicBoolean).get())
    }
    @Test fun visibleWakeBeforeMatchingWorkerAttachSurvivesUntilActualLoanIsReady() {
        val nextSurface=Any();val nextToken=Any();val replacementOwner=NativeSurfaceOwner(nextSurface,attachmentOwner=nextToken)
        admitted(2,replacementOwner)
        val requestedRevision=field("requestedCameraRevision").getLong(engine)
        val wake=assertNotNullRefresh(engine.prepareSurfaceRefresh(nextSurface,nextToken))
        assertTrue("Same-owner pending attach must retain its only visible wake",engine.applySurfaceRefresh(wake))
        assertFalse("The former native loan cannot render the replacement wake",(field("renderDirty").get(engine) as AtomicBoolean).get())
        assertTrue(attach(2,replacement,replacementOwner))
        assertTrue((field("renderDirty").get(engine) as AtomicBoolean).get())
        assertEquals(requestedRevision,field("requestedCameraRevision").getLong(engine));assertEquals(2L,nativeOwner)
        assertFalse(engine.applySurfaceRefresh(wake))
    }
    @Test fun bufferDiagnosticLookupDoesNotObserveAcknowledgeOrChangeAnyRenderingState() {
        val registry=field("bufferRegistry").get(engine) as ChartBufferRegistry
        val packet=registry.submit(registry.generation(),100,7,first,home,"test-only",sourceGeneration=11)!!
        val completed=registry.complete(packet.stamp,packet.frame)!!
        val before=engine.status.value
        assertEquals(completed,engine.chartBufferDiagnostics(packet.stamp))
        assertNull(engine.chartBufferDiagnostics(packet.stamp+1))
        assertEquals(0L,registry.observedStamp());assertEquals(before,engine.status.value)
        assertFalse((field("renderDirty").get(engine) as AtomicBoolean).get())
    }
    private fun assertNotNullRefresh(value:NativeSurfaceRefresh?):NativeSurfaceRefresh {
        assertNotNull(value);return requireNotNull(value)
    }
    @Test fun lifecycleDiagnosticsRemainBoundedWithoutSurfaceHandlesOrSessionValues() {
        for(command in 2L..100L){admitted(command);assertTrue(attach(command,first))}
        val lines=engine.surfaceOwnerDiagnostics().lines()
        assertEquals(65,lines.size) // One current snapshot and the last 64 lifecycle events.
        assertTrue(lines.last().contains("command=100 appliedAttach=100"))
        assertFalse(lines.any{it.contains("command=1 ")})
        assertTrue(lines.all{it.startsWith("surfaceOwner ")||it.matches(Regex("surface action=[A-Z_]+ command=[0-9]+ appliedAttach=[0-9]+ generation=[0-9]+ latestRequest=[0-9]+"))})
    }
}
