package com.no5.helm.engine

import com.no5.helm.chart.ChartEngineStatus
import kotlinx.serialization.json.*

/** A scripted native renderer benchmark never measures the cached Android outline View. */
internal fun touchRenderingMetadata(state: ChartEngineStatus, scriptedNativeFullRender: Boolean = false): JsonObject = buildJsonObject {
    val baseAvailable=state.confirmedFrame!=null || state.touchPreview?.baseFrame!=null
    val ready=state.touchOutlinesAvailable && state.outlines!=null && state.chartLoaded && state.surfaceAttached && baseAvailable
    put("touchOutlinesEnabled",state.touchOutlinesEnabled)
    put("configuredMode",if(state.bufferTransformMode != com.no5.helm.chart.ChartBufferTransformMode.OFF) state.bufferTransformMode.name else if(state.touchOutlinesEnabled)"OUTLINES" else "FULL_RENDERER")
    put("outlineCapabilityAvailable",state.touchOutlinesAvailable)
    put("outlineGeometryAvailable",state.outlines!=null)
    put("confirmedBaseAvailable",baseAvailable)
    put("touchPathAtSnapshot",when {
        scriptedNativeFullRender -> "not_exercised_scripted_native_full_renderer"
        state.bufferTransformMode != com.no5.helm.chart.ChartBufferTransformMode.OFF -> "buffer_transform_with_tagged_content_stage"
        !state.touchOutlinesEnabled -> "normal_native_motion_full_renderer"
        ready -> "cached_coast_outline_preview_then_full_renderer"
        else -> "full_renderer_preview_not_ready"
    })
    put("measurementInputPath",if(scriptedNativeFullRender)"scripted_native_camera" else "live_user_input")
    put("workerSamples",if (!scriptedNativeFullRender && state.bufferTransformMode != com.no5.helm.chart.ChartBufferTransformMode.OFF)
        "native_tagged_frames_classified_by_content_stage" else "native_full_render_frames_only")
    put("workerSampleEndpoint","native_render_return_and_status_refresh")
    put("ordinaryFullFrameConfirmation","native_producer_render_complete")
    put("previewFinalFrameConfirmation","matching_texture_consumer_timestamp_after_release")
    put("outlineViewFramesIncludedInWorkerSamples",false)
    put("normalFullRendererQueue","existing_conflated_render_requests_with_ordered_camera_motions")
    put("physicalDisplayLatencyMeasured",false)
    state.outlines?.let { outlines ->
        put("coastSegments",outlines.coast.size/4)
        put("toleranceMetres",outlines.toleranceMetres)
        put("geometryFingerprint",outlines.fingerprint)
    }
}

internal fun withTouchRenderingEndMetadata(start: JsonObject, state: ChartEngineStatus): JsonObject = buildJsonObject {
    start.forEach { (key,value) -> put(key,value) }
    val initial=start["touchRendering"]?.jsonObject
    val scripted=initial?.get("measurementInputPath")?.jsonPrimitive?.content=="scripted_native_camera"
    put("touchRenderingAtEnd",touchRenderingMetadata(state,scripted))
    val unchanged=initial?.get("touchOutlinesEnabled")?.jsonPrimitive?.booleanOrNull?.let { it==state.touchOutlinesEnabled }
    put("touchModeUnchanged",unchanged?.let { JsonPrimitive(it) }?:JsonNull)
}
