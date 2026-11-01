package com.no5.helm.engine
import com.no5.helm.chart.*
import kotlinx.serialization.json.*
import org.junit.Test
import org.junit.Assert.*

class TouchRenderingMetadataTest {
    @Test fun progressingPartialFramesCannotBeLabelledFullOnlyOrOutlinePerformance() {
        val m = touchRenderingMetadata(ready.copy(bufferTransformMode = ChartBufferTransformMode.TRANSFORM_AREAS_PROGRESS))
        assertEquals("TRANSFORM_AREAS_PROGRESS", m.getValue("configuredMode").jsonPrimitive.content)
        assertEquals("native_tagged_frames_classified_by_content_stage", m.getValue("workerSamples").jsonPrimitive.content)
        assertEquals("buffer_transform_with_tagged_content_stage", m.getValue("touchPathAtSnapshot").jsonPrimitive.content)
    }
    private val ready=ChartEngineStatus(chartLoaded=true,surfaceAttached=true,touchOutlinesAvailable=true,
        outlines=ChartOutlines(GeoProjection(42.36,-71.04),floatArrayOf(-100f,0f,100f,0f),20.0,"coast"),
        confirmedFrame=ChartFrame(1,"ready",ChartSurfaceGeometry.fit(2000,1200),ChartViewport(),null))
    @Test fun offMeansOrdinaryFullRendererEvenWhenTheCacheIsReady() {
        val m=touchRenderingMetadata(ready.copy(touchOutlinesEnabled=false))
        assertFalse(m.getValue("touchOutlinesEnabled").jsonPrimitive.boolean)
        assertEquals("normal_native_motion_full_renderer",m.getValue("touchPathAtSnapshot").jsonPrimitive.content)
        assertEquals("native_full_render_frames_only",m.getValue("workerSamples").jsonPrimitive.content)
    }
    @Test fun enabledButUnavailableCacheIsExplicitRatherThanLabelledOutlinePerformance() {
        val available=touchRenderingMetadata(ready);val fallback=touchRenderingMetadata(ready.copy(touchOutlinesAvailable=false))
        assertEquals("cached_coast_outline_preview_then_full_renderer",available.getValue("touchPathAtSnapshot").jsonPrimitive.content)
        assertEquals("full_renderer_preview_not_ready",fallback.getValue("touchPathAtSnapshot").jsonPrimitive.content)
        assertFalse(available.getValue("outlineViewFramesIncludedInWorkerSamples").jsonPrimitive.boolean)
        assertFalse(available.getValue("physicalDisplayLatencyMeasured").jsonPrimitive.boolean)
        assertEquals("native_producer_render_complete",available.getValue("ordinaryFullFrameConfirmation").jsonPrimitive.content)
        assertEquals("matching_texture_consumer_timestamp_after_release",available.getValue("previewFinalFrameConfirmation").jsonPrimitive.content)
    }
    @Test fun scriptedBostonTestsNeverClaimToExerciseTouchPreviewRegardlessOfSetting() {
        for(enabled in listOf(true,false)) {
            val m=touchRenderingMetadata(ready.copy(touchOutlinesEnabled=enabled),scriptedNativeFullRender=true)
            assertEquals(enabled,m.getValue("touchOutlinesEnabled").jsonPrimitive.boolean)
            assertEquals("not_exercised_scripted_native_full_renderer",m.getValue("touchPathAtSnapshot").jsonPrimitive.content)
            assertEquals("scripted_native_camera",m.getValue("measurementInputPath").jsonPrimitive.content)
        }
    }
    @Test fun finalReportKeepsFrozenStartAndTheSameScriptedOrLiveInputPathAtEnd() {
        for(scripted in listOf(false,true)) {
            val start=buildJsonObject { put("touchRendering",touchRenderingMetadata(ready,scripted)) }
            val report=withTouchRenderingEndMetadata(start,ready.copy(touchOutlinesAvailable=false))
            assertEquals(start.getValue("touchRendering"),report.getValue("touchRendering"))
            assertTrue(report.getValue("touchModeUnchanged").jsonPrimitive.boolean)
            assertEquals(if(scripted)"scripted_native_camera" else "live_user_input",report.getValue("touchRenderingAtEnd").jsonObject.getValue("measurementInputPath").jsonPrimitive.content)
            val changed=withTouchRenderingEndMetadata(start,ready.copy(touchOutlinesEnabled=false))
            assertFalse(changed.getValue("touchModeUnchanged").jsonPrimitive.boolean)
        }
    }
}
