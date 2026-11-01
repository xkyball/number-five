package com.no5.helm.gpu

import com.no5.domain.DepthUnit

/** New RAW waits for its matching consumed GPU buffer. Independent UI updates
 * and health changes still draw immediately, including while the GPU is late. */
internal class SonarGpuSceneUpdatePolicy {
    private var previous:SonarGpuFrame?=null
    private var previousUnit:DepthUnit?=null
    private var previousAudio:Any?=null
    private var previousHealth:List<Any?>?=null
    fun immediate(frame:SonarGpuFrame,unit:DepthUnit,audio:Any?,health:List<Any?>):Boolean {
        val before=previous
        val needed=before==null||before.displayRevision!=frame.displayRevision||
            before.context!=frame.context||before.ringGeneration!=frame.ringGeneration||
            before.display.freeze!=frame.display.freeze||before.night!=frame.night||before.dashboardPreview!=frame.dashboardPreview||
            previousUnit!=unit||previousAudio!==audio||previousHealth!=health
        previous=frame;previousUnit=unit;previousAudio=audio;previousHealth=health
        return needed
    }
}

/** Equality of values cannot substitute for the exact consumed producer buffer. */
internal object SonarCompositeRecordBinding {
    fun matches(completion:SonarGpuCompletion?,recorded:SonarGpuCompletion?,bufferNotified:Boolean):Boolean =
        completion!=null&&recorded===completion&&bufferNotified
}
