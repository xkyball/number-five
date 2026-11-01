package com.no5.helm.chart

/** Opaque reference to one owner-captured current view. This never supplies a source or camera. */
data class CurrentResidentLiveArm(val snapshotId: String) {
    init { require(java.util.UUID.fromString(snapshotId).toString() == snapshotId) }
}
