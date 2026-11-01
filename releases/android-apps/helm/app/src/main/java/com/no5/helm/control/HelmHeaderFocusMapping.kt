package com.no5.helm.control

import org.lumina.header.consumer.v1.HeaderConsumerContract.RootFocus

/** Only HelmSystemControl's accepted Guard path creates production values.
 * External Root epoch is never copied into the independently monotonic host epoch. */
internal data class HelmHeaderFocusMapping(
    val rootControlSession: String?,
    val rootFocus: RootFocus?,
    val hostForegroundEpoch: Long,
) {
    fun sameScope(other: HelmHeaderFocusMapping?): Boolean = other != null &&
        rootControlSession == other.rootControlSession && hostForegroundEpoch == other.hostForegroundEpoch &&
        rootFocus != null && rootFocus.sameIdentity(other.rootFocus)
}
