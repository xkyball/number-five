package com.no5.helm.control

import com.no5.control.NormalizedKnobInput

/**
 * HELM-owned long-press extension point for the durable router.
 *
 * The system router only identifies and forwards a long press.  It never
 * translates it into Android media keys or navigation.  Product code may
 * install a capability-checked handler; a missing handler rejects the action
 * without replaying it elsewhere.
 */
internal fun interface HelmAudioSpecialActions {
    fun perform(input: NormalizedKnobInput): Boolean

    companion object {
        val NONE = HelmAudioSpecialActions { false }
    }
}
